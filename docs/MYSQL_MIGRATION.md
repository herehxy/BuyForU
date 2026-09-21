# Commerce 持久化迁移到 MySQL 的设计与实施说明

> 文档基线：`fix/correctness-and-acceptance` 分支当前工作区
> 文档日期：2026-09-08
> 目标架构：**双库**——MySQL 承载交易事实，PostgreSQL 保留向量知识与 LangGraph checkpoint。

## 0. 结论

| 范围 | 是否迁移 | 理由 |
| --- | --- | --- |
| `commerce-service`（订单、库存、预占、effect ledger、Outbox） | **迁移** | 全部为关系事务与行锁，MySQL 8.0.34+ 可承载 |
| `agent-app`（pgvector 知识库、LangGraph checkpoint、命令与租约） | **不迁移** | pgvector 在 MySQL 无对等实现，见 §1.2 |

迁移能够成立的前提是 `CommerceGateway` 已经把交易持久化封在领域边界内：Agent 只依赖该接口，替换 Commerce 的数据库对 Agent 完全透明。

## 1. 目标架构与范围

### 1.1 双库划分

```text
MySQL 8.0.34+    交易主库：product / sku / inventory / inventory_reservation
                          / confirmable_snapshot / orders / effect_record / outbox_event
                          / customer_address / delivery_zone / promotion / shipping_rule
PostgreSQL 17    Agent 侧保留：knowledge_chunk（pgvector）、langgraph_checkpoint
                              / agent_run / agent_command / agent_run_execution
```

两个库由**两个独立服务**持有，不需要在同一个应用里配双数据源。`agent-app` 继续连 PostgreSQL，`commerce-service` 改连 MySQL。

### 1.2 为什么 Agent 侧不迁

| 能力 | 依赖 | MySQL 现状 | 结论 |
| --- | --- | --- | --- |
| 知识检索 | pgvector 向量列 + 近似索引 | MySQL 8.0 无向量类型；9.0 的 `VECTOR` 无 ANN 索引，社区版只能全表扫描 | 不可迁移 |
| LangGraph checkpoint | JSON 列 + upsert | 语法可迁，但收益为零 | 不必要 |
| 命令与租约 | `FOR UPDATE`、行锁 | 可迁，但与 checkpoint 同库 | 不必要 |

向量检索若必须离开 pgvector，正确做法是换专用向量库（Milvus、Qdrant、Elasticsearch `dense_vector`），属于另一项独立改造，不在本文档范围。

## 2. 版本与环境硬要求

| 要求 | 值 | 原因 |
| --- | --- | --- |
| MySQL 版本 | **≥ 8.0.34** | 8.0.16 起 `CHECK` 约束才真正生效；8.0.1 起支持 `FOR UPDATE SKIP LOCKED` |
| 隔离级别 | **`READ-COMMITTED`** | 默认 `REPEATABLE READ` 的间隙锁会显著抬高库存争用下的死锁率，见 §4.2 |
| binlog 格式 | `ROW` | RC 隔离级别下的官方要求 |
| 字符集 | `utf8mb4` / `utf8mb4_0900_ai_ci` | 避免索引长度与排序差异 |
| 时间类型 | `DATETIME(6)` | 替代 `TIMESTAMPTZ`；`TIMESTAMP` 有 2038 上限，不用 |

启动前确认：

```sql
SELECT @@transaction_isolation, @@binlog_format, @@version;
-- 期望：READ-COMMITTED | ROW | 8.0.34 及以上
```

## 3. 方言改造点（逐项前后对照）

### 3.1 迁移脚本

| # | 位置 | PostgreSQL（现状） | MySQL（改造后） |
| --- | --- | --- | --- |
| 1 | `V1:8` | `attributes JSONB NOT NULL DEFAULT '{}'::jsonb` | `attributes JSON NOT NULL`（见 §3.1.1） |
| 2 | `V1:21,28` | `CHECK (available_quantity >= 0)` | 保留，但**必须 ≥ 8.0.16**，见 §4.1 |
| 3 | `V1:30,31,40,41,52` | `TIMESTAMPTZ` | `DATETIME(6)` |
| 4 | `V1:39,50,62,72` | `JSONB` | `JSON` |
| 5 | `V2:8,14,20` | `ON CONFLICT (...) DO NOTHING` | `ON DUPLICATE KEY UPDATE product_id = product_id` |
| 6 | `V4:1,2` | `CREATE SEQUENCE ... START WITH 1` | 生成器表，见 §3.1.2 |
| 7 | `V4:4-6` | `CREATE INDEX ... WHERE status = 'ACTIVE'` | 复合索引 `(status, expires_at)`，见 §3.1.3 |
| 8 | `V4:8-12` | `ADD COLUMN IF NOT EXISTS` | 去掉 `IF NOT EXISTS`，见 §3.1.4 |
| 9 | `V4:15-17` | `CREATE INDEX ... WHERE status = 'PENDING'` | 复合索引 `(status, next_attempt_at, created_at)` |
| 10 | `V3:18-19` | `... ON customer_address(user_id, address_id) WHERE active` | 复合索引 `(user_id, active, address_id)` |
| 11 | `V5:4-6` | `... ON customer_address(user_id, created_at DESC) WHERE active` | 复合索引 `(user_id, active, created_at)`，降序用 `created_at DESC` |
| 12 | `V5:2` | `ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ` | 去掉 `IF NOT EXISTS`，改 `DATETIME(6)` |
| 13 | `V3:16` | `ON CONFLICT (zone_code) DO NOTHING` | `ON DUPLICATE KEY UPDATE zone_code = zone_code` |
| 14 | 全部 | `CREATE SCHEMA IF NOT EXISTS commerce_schema` | `CREATE DATABASE IF NOT EXISTS commerce_schema` |

#### 3.1.1 JSON 列默认值（容易漏）

MySQL 的 `JSON` 列**不接受字面量默认值**，且 8.0.13 之前连表达式默认值都不支持。现状 `'{}'::jsonb` 必须去掉，改为允许 `NULL` 并由应用层保证非空；若坚持要默认值，8.0.13+ 可写：

```sql
attributes JSON NOT NULL DEFAULT (JSON_OBJECT())
```

但项目里 `attributes` 恒由 Java 侧写入完整 JSON，去掉默认值最省事，且不改变语义。

#### 3.1.2 序列替代：`nextval()` → 生成器表

现状 `JdbcCommerceEngine.java:506-513`：

```java
Long value = jdbc.queryForObject("SELECT nextval('commerce_schema." + sequence + "')", Long.class);
```

MySQL 无 `SEQUENCE`。改用生成器表（利用 `AUTO_INCREMENT` 原子性）：

```sql
CREATE TABLE IF NOT EXISTS commerce_schema.id_generator (
    gen_name  VARCHAR(64) PRIMARY KEY,
    next_val  BIGINT NOT NULL DEFAULT 1
) ENGINE = InnoDB;
INSERT INTO commerce_schema.id_generator (gen_name, next_val)
VALUES ('quote_version_seq', 1), ('snapshot_version_seq', 1);
```

Java 侧改为：

```java
private long nextVersion(String sequence) {
    if (!List.of("quote_version_seq", "snapshot_version_seq").contains(sequence)) {
        throw new IllegalArgumentException("unsupported version sequence");
    }
    jdbc.update("""
            UPDATE commerce_schema.id_generator
            SET next_val = LAST_INSERT_ID(next_val + 1) WHERE gen_name = ?
            """, sequence);
    Long value = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    if (value == null) throw new IllegalStateException("database did not generate a version");
    return value;
}
```

`LAST_INSERT_ID(expr)` 是**会话级**的，不产生跨会话串号，也不需要显式加锁。代价是这两行成为热点行，当前规模无影响；若将来成为瓶颈，改为步长预取（每次取 100 个在内存分配）。

#### 3.1.3 部分索引替代

现状（`V4:4-6`）：

```sql
CREATE INDEX IF NOT EXISTS ix_reservation_expiry
    ON commerce_schema.inventory_reservation(expires_at)
    WHERE status = 'ACTIVE';
```

MySQL 不支持部分索引，改为前缀列复合索引：

```sql
CREATE INDEX ix_reservation_expiry
    ON commerce_schema.inventory_reservation(status, expires_at);
```

注意索引列顺序：`status` 在前才能真正被 `WHERE status='ACTIVE' AND expires_at <= ?` 用上。Outbox 的 `ix_outbox_dispatchable` 同理改为 `(status, next_attempt_at, created_at)`。

#### 3.1.4 DDL 的 `IF NOT EXISTS`

MySQL 的 `ALTER TABLE ... ADD COLUMN` **不支持 `IF NOT EXISTS`**。由于 Flyway 保证每个版本只执行一次，直接去掉即可：

```sql
ALTER TABLE commerce_schema.outbox_event
    ADD COLUMN attempts        INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME(6)  NOT NULL DEFAULT NOW(6),
    ADD COLUMN last_error      TEXT,
    ADD COLUMN published_at    DATETIME(6);
```

**另有一个 MySQL 特有的 DDL 风险：DDL 语句会触发隐式提交，不在事务内。** 因此 Flyway 迁移脚本中途失败时，已执行的 DDL 不会回滚，修复后不能简单重跑——需要在继续前手工清理残留对象，或把每个迁移脚本设计成可重复执行。

### 3.2 Java 侧 SQL

| # | 位置 | 现状 | 改造后 |
| --- | --- | --- | --- |
| 15 | `JdbcCommerceEngine:216` | `SELECT pg_advisory_xact_lock(hashtextextended(?, 0))` | **不能直接删**，见 §4.4 |
| 16 | `JdbcCommerceEngine:432` | 同上（`beginEffect` 内） | 删除 + 冲突重试，见 §4.3 |
| 17 | `JdbcCommerceEngine:97,343,352,405` | `col::text` | 直接读列，去掉 `::text` |
| 18 | `JdbcCommerceEngine:436` | `result_payload::text` | `result_payload` |
| 19 | `JdbcCommerceEngine:292,377,384,460` | `CAST(? AS jsonb)` | `CAST(? AS JSON)` |
| 20 | `JdbcCommerceEngine:510` | `nextval(...)` | 生成器表，见 §3.1.2 |
| 21 | `OutboxDispatcher:65` | `payload::text` | `payload` |
| 22 | `OutboxDispatcher:59` | `now() - interval '60 seconds'` | `NOW(6) - INTERVAL 60 SECOND` |
| 23 | `OutboxDispatcher:69` | `FOR UPDATE SKIP LOCKED` | **不变**（8.0.1+ 原生支持） |
| 24 | `OutboxDispatcher` 各时间列 | `now()` | `NOW(6)`（保持微秒精度一致） |

`::text` 可以整段删除的原因：MySQL 的 `JSON` 列经 JDBC 读回即为 `String`，`result.getString(n)` 行为一致，不需要显式转换。

## 4. 四个高风险点

### 4.1 `CHECK` 约束可能被静默忽略

`V1:21` 的 `CHECK (available_quantity >= 0)` 是防超卖的**最后一道防线**——应用层行锁和乐观锁都写错时，它仍能兜住。MySQL 在 **8.0.16 之前会解析这条语句然后丢弃**，不报错、不生效。

**验证方法**（迁移后必做）：

```sql
-- 1) 确认约束真实存在
SELECT CONSTRAINT_NAME, CHECK_CLAUSE
FROM information_schema.CHECK_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'commerce_schema';

-- 2) 确认它真的拦得住（预期报错 3819）
UPDATE commerce_schema.inventory SET available_quantity = -1 WHERE sku_id = 'sku-air-16';
```

第 2 步如果成功执行，说明版本不对，必须升级——此时"防超卖"只剩应用层两道保险。

### 4.2 默认隔离级别不同，锁行为不同

PostgreSQL 默认 `READ COMMITTED`，MySQL 默认 `REPEATABLE READ` 且带**间隙锁**。库存扣减与预占回收都是热点行争用，迁到 RR 后：

- 范围扫描（`WHERE status='ACTIVE' AND expires_at <= ?`）会加**间隙锁**，把不该锁的行区间也锁住
- 并发插入 effect（§4.3）在间隙锁下更容易命中**死锁（1213）**而非唯一键冲突（1062）

**必须**在 `my.cnf` 或启动时固定：

```ini
[mysqld]
transaction_isolation = READ-COMMITTED
binlog_format         = ROW
```

**验证方法**：用现有并发测试（见 §6）对比迁移前后的失败率与耗时；同时在 RC 下重跑，确认死锁计数为 0。

### 4.3 advisory lock 无对等物，且不能简单删除

现状 `JdbcCommerceEngine.java:429-455`：

```java
// advisory lock 先串行化同一幂等键；requestHash 再防止同一键被误用于不同业务请求。
jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        preparedStatement -> preparedStatement.setString(1, effect.idempotencyKey()),
        resultSet -> null);
List<EffectRow> rows = jdbc.query("""
        SELECT operation_type, request_hash, status, result_payload::text
        FROM commerce_schema.effect_record
        WHERE effect_id = ? OR idempotency_key = ? FOR UPDATE
        """, ...);
if (!rows.isEmpty()) { /* 重放或抛 EFFECT_CONFLICT */ }
jdbc.update("""
        INSERT INTO commerce_schema.effect_record
            (effect_id, operation_type, idempotency_key, request_hash, status)
        VALUES (?, ?, ?, ?, 'PENDING')
        """, ...);
```

MySQL 的 `GET_LOCK()` 是**会话级命名锁，不随事务提交释放**，不能替代事务级 advisory lock。

好消息是**唯一索引本身已经提供了互斥**：`ux_effect_record_idempotency_key` 保证同一幂等键只有一行。改造方案是删掉 advisory lock，改为**捕获插入冲突后重查重放**：

```java
private <T> T beginEffect(EffectContext effect, String operation, String requestHash, Class<T> type) {
    // MySQL 无事务级 advisory lock；ux_effect_record_idempotency_key 唯一索引提供互斥。
    // 并发下第二个事务会在插入时冲突（1062）或被间隙锁判死锁（1213），都需要重查重放。
    List<EffectRow> rows = selectEffectForUpdate(effect);
    if (!rows.isEmpty()) return replayOrThrow(rows, operation, requestHash, type);

    try {
        jdbc.update("""
                INSERT INTO commerce_schema.effect_record
                    (effect_id, operation_type, idempotency_key, request_hash, status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """, effect.effectId(), operation, effect.idempotencyKey(), requestHash);
    } catch (DuplicateKeyException | CannotAcquireLockException conflict) {
        rows = selectEffectForUpdate(effect);
        if (rows.isEmpty()) throw conflict;          // 不是幂等键冲突，原样抛出
        return replayOrThrow(rows, operation, requestHash, type);
    }
    return null;
}
```

要点三条：

1. **两个异常都要捕获**。RR 下更可能抛 `CannotAcquireLockException`（死锁 1213），RC 下更可能是 `DuplicateKeyException`（1062）。只捕获一个会在另一种隔离级别下漏掉。
2. **捕获后必须重查**，不能直接吞掉后继续——否则会丢失"重放上次结果"这个语义。
3. **重查仍为空则原样抛出异常**，避免把真正的故障伪装成幂等成功。

### 4.4 地址登记那处 advisory lock **不能**直接删除

`JdbcCommerceEngine:216` 的 advisory lock 与 effect 那处**性质不同**，不能套用 §4.3 的做法。

原因：effect 有 `ux_effect_record_idempotency_key` 唯一索引兜底，而 `customer_address`（`V3:7-12`）**只有主键 `address_id`（随机 UUID），没有任何唯一约束**。登记地址的逻辑是"先查 `user_id + zone_code + active`，查不到才插入"——`SELECT ... FOR UPDATE` 在**结果集为空时不锁任何行**，两个并发请求会同时查到"不存在"并各自插入，产生两条重复地址。PostgreSQL 靠 advisory lock 串行化，MySQL 删掉它就会退化。

正确做法是补一个等价的**部分唯一索引**。MySQL 不支持 `WHERE active` 的部分索引，用虚拟生成列 + 唯一索引实现：

```sql
ALTER TABLE commerce_schema.customer_address
    ADD COLUMN active_zone VARCHAR(32)
        GENERATED ALWAYS AS (IF(active, zone_code, NULL)) VIRTUAL;

CREATE UNIQUE INDEX ux_customer_address_active_zone
    ON commerce_schema.customer_address(user_id, active_zone);
```

原理：`active` 为真时 `active_zone = zone_code`，为假时为 `NULL`；**MySQL 唯一索引允许多个 `NULL`**，因此约束效果恰好等于"每个用户每个区域最多一条激活地址"，历史停用记录不受影响。

加上这个索引后，`JdbcCommerceEngine:216` 的 advisory lock 才可以删除，并同样按 §4.3 捕获 `DuplicateKeyException` 后重查返回已有地址。

**这一步不能省**，否则会出现用户重复登记同一配送区域、前端刷新后地址列表出现重复项的静默数据缺陷。

## 5. Flyway 多方言组织

推荐按目录分离，用 Spring profile 切换，避免同一份脚本里塞 `if dialect` 判断：

```text
commerce-service/src/main/resources/
└── db/
    ├── migration/            # 现有 PostgreSQL 脚本，保持默认
    └── migration-mysql/      # MySQL 脚本
```

`application-mysql.yml`：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/commerce_schema?useSSL=false&serverTimezone=UTC&characterEncoding=utf8mb4}
    username: ${DB_USER:buyforu}
    password: ${DB_PASSWORD:buyforu}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      transaction-isolation: TRANSACTION_READ_COMMITTED   # 双保险，不依赖服务端默认
  flyway:
    locations: classpath:db/migration-mysql
    # MySQL 的 schema 等价于 database
    default-schema: commerce_schema
    schemas: commerce_schema
```

`pom.xml` 增加 MySQL 驱动，并把 PostgreSQL 驱动改为非必需（或保留，便于双库并行开发）：

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

激活方式：`--spring.profiles.active=mysql` 或环境变量 `SPRING_PROFILES_ACTIVE=mysql`。

## 6. 验证清单

迁移是否成功，以**现有行为不退化**为准，不以"能启动"为准。

| 验证项 | 现有测试 | 迁移后必须仍然通过的原因 |
| --- | --- | --- |
| 库存并发不超卖 | Commerce 集成测试 | 行锁（含锁内检查）+ CHECK 约束两道，缺一不可；另需覆盖预占状态机防重复释放导致库存虚增 |
| effect 冲突与重放 | Commerce 集成测试 | 直接验证 §4.3 的冲突重试分支 |
| 订单按快照解析、幂等创建 | Commerce 集成测试 | 验证 `source_snapshot_id` 唯一约束与 JSON 读写 |
| Outbox 投递与重试 | Commerce 集成测试 | 验证 `SKIP LOCKED` 与 `INTERVAL` 语法改造 |
| 过期预占回收 | `ReservationExpiryJob` 测试 | 验证复合索引替代部分索引后仍走索引 |
| 版本号生成 | 快照/报价版本测试 | 验证 §3.1.2 生成器表无串号 |
| 地址登记幂等 | **新增**（现有未覆盖） | 并发同一 `user_id + zone_code` 只产生一条激活地址，验证 §4.4 唯一索引 |

集成测试容器相应换成 MySQL（Testcontainers `MySQLContainer`），或保留 PG 测试并用 `mysql` profile 跑一套镜像测试。

另加两项**迁移专属**验证（不属于现有测试）：

```sql
-- A) 约束确实生效（见 §4.1）
UPDATE commerce_schema.inventory SET available_quantity = -1 WHERE sku_id='sku-air-16';  -- 必须失败

-- B) 隔离级别与 binlog（见 §4.2）
SELECT @@transaction_isolation, @@binlog_format;   -- READ-COMMITTED / ROW
```

## 7. 实施顺序

严格按序，**不要并行**，每一步都要能独立回滚。

1. 建 `db/migration-mysql/` 与 `application-mysql.yml`，接入 MySQL 驱动（不改业务代码，先保证能连上）。
2. 迁移脚本全部改写（§3.1），跑空库 Flyway。
3. `CHECK` 与隔离级别验证（§4.1、§4.2）——**不通过不要往下走**。
4. Java 侧方言改造（§3.2 的 13–20 项，低风险，可批量）。
5. `nextVersion` 改生成器表（§3.1.2）。
6. advisory lock 改造与冲突重试（§4.3），**单独一个提交**，便于 review 与回滚。
   ——同时必须完成 §4.4 的 `ux_customer_address_active_zone` 唯一索引，**两者一起改，不能只做前者**。
7. 全量集成测试换 MySQL 跑通（§6）。
8. 压测：并发扣减同一 SKU，观察死锁计数与 p95，与 PG 基线对比。

工作量参考：第 1–4 步约 2 天，第 5–6 步约 1.5 天，第 7–8 步约 1.5 天，合计约 5 个工作日（不含调优与评审往返）。

## 8. 已知限制

| 限制 | 说明 |
| --- | --- |
| DDL 无事务 | MySQL DDL 隐式提交，迁移脚本中途失败会留下部分对象，需手工清理后重跑 |
| JSON 无部分索引/表达式索引需生成列 | 若将来要按 JSON 内字段检索，必须建生成列再建索引 |
| `id_generator` 是热点行 | 当前规模无影响；高并发下改步长预取 |
| 向量检索仍依赖 PostgreSQL | 双库是长期形态，不是过渡态 |
| 字符集与排序规则 | `utf8mb4_0900_ai_ci` 对大小写不敏感，若业务依赖大小写区分需显式指定 collation |
| 部分唯一索引 | MySQL 不支持 `WHERE` 部分索引，唯一性约束需借虚拟生成列实现，见 §4.4 |
| 地址登记现有测试未覆盖幂等 | 迁移时必须补一条并发登记同一区域的测试，否则 §4.4 的缺陷不会被发现 |
