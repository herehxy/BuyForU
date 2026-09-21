# BuyForU 重难点实现详解

> 文档基线：`fix/correctness-and-acceptance` 分支当前工作区
> 文档日期：2026-09-08
> 定位：回答"重难点是什么、为什么难、代码怎么实现"。所有代码片段均取自当前代码，标注 `文件:行号`。
> 配套阅读：`docs/PROJECT_DESIGN.md`（设计全貌与 Q1–Q37）、`docs/CODE_GUIDE.md`（代码结构导览）。

## 0. 总览

| # | 重难点 | 难的本质 | 核心手段 |
| --- | --- | --- | --- |
| 1 | 交易一致性 | 长链路 + 人工等待，任何中断重试都会重复扣库存/重复下单 | 三层幂等 + 行锁与 CHECK 防超卖 + effect ledger |
| 2 | 崩溃恢复 | 状态能重放、副作用不能重做，两者却交织在同一条流里 | 三层状态分工 |
| 3 | 慢调用不拖垮数据库 | LLM 几秒到几十秒，若持事务则连接池秒光 | 202 异步化 + 短事务 claim + 租约栅栏 |
| 4 | 租约与栅栏的正确性 | 分布式下"我以为还在跑"与"别人已接管"的判定 | 30s 租约 + epoch + `deadlineAt` 判据 |
| 5 | Agent 与交易域的边界 | 大模型不确定，交易要求确定，且需可归责 | 固定图 + LLM 只产数据 + 关闭工具回调 |
| 6 | 多实例限流与公平调度 | 单实例内存限流失效；高频用户垄断执行槽 | Redis Lua 令牌桶 + 虚拟时间公平队列 |
| 7 | SSE 断线恢复 | 刷新或实例切换丢进度 | 事件先落 PG，Redis 只唤醒；`Last-Event-ID` 续传 |

---

## 1. 交易一致性

### 1.1 难在哪

一次购物要穿过 LLM 规划、向量检索、两次人工等待、MCP 网络调用。任何一步超时或重试，都会带来两类问题：

- **重复做**：库存扣两次、订单建两笔
- **做一半**：库存扣了、订单没建

普通 Web 项目靠"数据库唯一约束 + 事务"就能解决大半，但这里不行——链路里有**不可回滚的外部动作**和**分钟级的人工等待**，事务边界根本盖不住全程。

### 1.2 设计思路：把两个问题拆开治

不试图用一个"分布式事务"解决全部，而是拆成两条独立防线：

```text
重复执行  →  三层幂等（入口 / 领域 / 恢复）
库存超卖  →  行锁 + CHECK 兜底；库存虚增  →  预占状态机 + effect ledger
```

### 1.3 实现：三层幂等

**第一层｜入口：`Idempotency-Key`**（`CommandService.java:43,62-64`）

```java
var existing = commands.findByIdempotency(userId, runId, idempotencyKey);
if (existing.isPresent()) return replay(existing.get(), runId, requestHash);
// ...
try {
    commands.insert(command);
} catch (DataIntegrityViolationException race) {
    // 并发下唯一索引冲突，重查后重放，而不是抛错给用户
    return replay(commands.findByIdempotency(userId, runId, idempotencyKey).orElseThrow(() -> race), runId, requestHash);
}
```

注意 `catch (DataIntegrityViolationException)` 这一段：**唯一索引是最终裁判，异常不是错误而是并发信号**。少了它，两个并发请求仍会有一条失败。

**第二层｜领域：effect ledger**（`JdbcCommerceEngine.java:429-455`）

```java
private <T> T beginEffect(EffectContext effect, String operation, String requestHash, Class<T> type) {
    // advisory lock 先串行化同一幂等键；requestHash 再防止同一键被误用于不同业务请求。
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            preparedStatement -> preparedStatement.setString(1, effect.idempotencyKey()),
            resultSet -> null);
    List<EffectRow> rows = jdbc.query("""
            SELECT operation_type, request_hash, status, result_payload::text
            FROM commerce_schema.effect_record
            WHERE effect_id = ? OR idempotency_key = ? FOR UPDATE
            """, ...);
    if (!rows.isEmpty()) {
        EffectRow row = rows.getFirst();
        if (!operation.equals(row.operation()) || !requestHash.equals(row.requestHash())) {
            throw new CommerceException("EFFECT_CONFLICT", "effect key was reused for another request");
        }
        if ("COMPLETED".equals(row.status())) return json.readValue(row.result(), type);
        throw new CommerceException("EFFECT_IN_PROGRESS", "effect is already in progress");
    }
    jdbc.update("""
            INSERT INTO commerce_schema.effect_record
                (effect_id, operation_type, idempotency_key, request_hash, status)
            VALUES (?, ?, ?, ?, 'PENDING')
            """, effect.effectId(), operation, effect.idempotencyKey(), requestHash);
    return null;
}
```

四道机关缺一不可：

| 机关 | 挡住什么 |
| --- | --- |
| `pg_advisory_xact_lock` | 同一幂等键的并发进入 |
| `SELECT ... FOR UPDATE` | 读到未提交/正在进行的记录 |
| `requestHash` 校验 | **同一把键被拿去做不同的业务请求**（否则会稀里糊涂重放错误结果） |
| 三态判定 | 已完成→重放结果；进行中→`EFFECT_IN_PROGRESS`；没有→插入 |

底层约束（`V2__seed_and_idempotency.sql:1-2`）：

```sql
CREATE UNIQUE INDEX IF NOT EXISTS ux_effect_record_idempotency_key
    ON commerce_schema.effect_record (idempotency_key);
```

**第三层｜恢复：按来源快照查订单**（`JdbcCommerceEngine.java:350-360`）

```java
// 即使客户端换了新的网络幂等键，只要来源快照相同，也只能存在一个订单。
Order existingOrder = jdbc.query("""
        SELECT order_payload::text FROM commerce_schema.orders
        WHERE source_snapshot_id = ?
        FOR UPDATE
        """, ...).stream().findFirst().orElse(null);
if (existingOrder != null) {
    completeEffect(effect, existingOrder.orderId(), existingOrder);
    return existingOrder;
}
```

这一层覆盖的是**最凶险的窗口**：下单已成功、但 Agent 还没保存状态就崩了。此时客户端重试会带**新的**幂等键，前两层全部失效，只有按 `source_snapshot_id` 查才能兜住。

### 1.4 实现：防超卖与防库存错乱

```java
// JdbcCommerceEngine.java:256-268
Integer stock = jdbc.query("""
        SELECT available_quantity FROM commerce_schema.inventory WHERE sku_id = ? FOR UPDATE
        """, ..., request.skuId()).stream().findFirst()
        .orElseThrow(() -> new CommerceException("SKU_NOT_FOUND", "unknown sku: " + request.skuId()));
if (stock < request.quantity()) throw new CommerceException("OUT_OF_STOCK", "insufficient inventory");
// ...
jdbc.update("""
        UPDATE commerce_schema.inventory
        SET available_quantity = available_quantity - ?, version = version + 1 WHERE sku_id = ?
        """, request.quantity(), request.skuId());
```

**关键不在"加锁"本身，而在于加锁、检查、扣减三步处在同一个事务里**——锁从 `SELECT` 一直持有到事务提交。少了任何一步都不行：先查后扣不加锁，两个事务会读到同一个值；加锁但检查在锁外，读到的是陈旧值。

以最后 1 件商品被两人同时下单为例：

| 场景 | 时序 | 结果 |
| --- | --- | --- |
| 无行锁 | T1 读 1 → T2 读 1 → T1 写 0 → T2 写 -1 | 超卖 |
| `FOR UPDATE` | T1 读 1 加锁 → T2 阻塞 → T1 写 0 提交 → T2 读到 0 | 正确拒绝 |

**库存错乱有两个方向**，不能只防"扣成负数"：

| 机制 | 位置 | 防什么 |
| --- | --- | --- |
| `FOR UPDATE` 行锁 | `:257` | 并发读-改-写竞态（超卖）——**核心** |
| `CHECK (available_quantity >= 0)` | `V1__commerce_core.sql:21` | 应用层写错导致负库存——**不可绕过的兜底** |
| 预占状态机（仅 `ACTIVE` 才归还） | `:321` | 重复释放导致**库存虚增** |
| effect ledger | `:429` | 重复预占 |

`CHECK` 那道是故意加的，体现"防御纵深"：应用层两道都写错（例如把 `-` 写成 `+`、或新增代码路径忘了加锁），数据库仍然不让库存变负。

释放路径的状态判断（`:321-328`）同样不可省：

```java
if (ReservationStatus.ACTIVE.name().equals(row.status())) {
    jdbc.update("UPDATE ... SET status = 'RELEASED' WHERE reservation_id = ?", reservationId);
    jdbc.update("UPDATE ... SET available_quantity = available_quantity + ? ...", row.quantity(), row.skuId());
}
```

没有 `ACTIVE` 判断，同一笔预占被释放两次，库存就凭空多出来。

#### 关于 `version` 列：它不是乐观锁

三处 inventory 的 UPDATE（`:266` 扣减、`:325` 释放、`:495` 过期回收）都写成：

```sql
SET available_quantity = available_quantity - ?, version = version + 1 WHERE sku_id = ?
```

**WHERE 里没有 `AND version = ?`**，因此它只是**变更计数器**（审计与排查用），不构成乐观锁。真正的并发保护完全由 `FOR UPDATE` 提供。

这在当前设计下是合理的：行锁已经把同一 SKU 的操作串行化，再加版本校验属于冗余。若要让它成为真正的乐观锁，需改为：

```sql
UPDATE ... SET available_quantity = available_quantity - ?, version = version + 1
WHERE sku_id = ? AND version = ?     -- 追加版本条件，并检查影响行数是否为 1
```

**面试提醒**：不要把这个字段说成乐观锁，容易被追问穿。正确说法见 §8。

---

## 2. 崩溃恢复：三层状态分工

### 2.1 难在哪

业务流里同时存在两类数据，**恢复策略完全相反**：

- 业务状态、图游标 → **可以重放**（重放无副作用）
- 预占、下单、释放 → **不能重做**（重做 = 重复交易）

如果只用 LangGraph checkpoint，重放会把"已经成功的下单"再执行一次。

### 2.2 设计思路

```text
ShoppingAgentState      业务状态    → 可重放
LangGraph checkpoint    图游标      → 可重放
effect_record           副作用账本  → 只重放结果，绝不重做
```

一句话原则：**状态可以重放，副作用不能重做——所以副作用必须单独记账。**

### 2.3 实现

重放分支就是 §1.3 里那一行：

```java
if ("COMPLETED".equals(row.status())) return json.readValue(row.result(), type);
```

`createOrder` 的 `source_snapshot_id` 查重（§1.3 第三层）是崩溃窗口的兜底。

### 2.4 一个必须内化的规则

**MCP 写超时不能直接判定失败。** 超时只说明"不知道结果"，正确动作是按幂等键查 effect，而不是重试。这也是 `CommerceGateway.findOrderBySnapshot` 这个只读方法要单独暴露给 Agent 的原因——**给 Agent 一个"问结果"的通道，它才不会瞎猜**。

---

## 3. 慢调用不拖垮数据库

### 3.1 难在哪

一次规划几十秒。如果按普通 Web 请求那样"开事务 → 调 LLM → 提交"，连接池会在几秒内耗尽，整个服务不可用。这是 Agent 类项目最常见的死法。

### 3.2 设计思路

核心目标只有一个：**把"持有数据库连接的时间"压到最短**，而不是"让 LLM 变快"。

```text
写请求 → 202 落命令（短事务，毫秒级）→ 返回
                    ↓
Worker → 短事务 claim（拿租约，立即提交）→ 全程无连接地跑 LLM → 短事务写回
```

### 3.3 实现

**调度入口**（`CommandWorker.java:64-73`）

```java
@Scheduled(fixedDelay = 100, scheduler = "dispatchScheduler")
void dispatch() {
    dispatchLane(AgentCommand.QueueClass.PLANNING, planningPermits, planning);
    dispatchLane(AgentCommand.QueueClass.TRANSACTION, transactionPermits, transaction);
    for (AgentCommand command : commands.controlReady(properties.controlWorkers())) {
        if (!controlPermits.tryAcquire()) break;
        control.submit(() -> execute(command, controlPermits, false));
    }
}
```

三条泳道各有独立信号量（`CommandWorker.java:59-61`）：

```java
this.planningPermits    = new Semaphore(properties.planningWorkers());
this.transactionPermits = new Semaphore(properties.transactionWorkers());
this.controlPermits     = new Semaphore(properties.controlWorkers());
```

**出队与执行**（`CommandWorker.java:161-179`）

```java
private void dispatchLane(AgentCommand.QueueClass lane, Semaphore permits, ExecutorService executor) {
    if (!permits.tryAcquire()) return;
    UUID id;
    try { id = fairQueue.poll(lane); }
    catch (RuntimeException unavailable) { permits.release(); return; }
    // ...
    if (!fairQueue.tryAcquireUser(command.userId(), command.commandId())) {
        fairQueue.enqueueFront(command);   // 抢不到许可放回队头，不掉队尾
        permits.release();
        return;
    }
    executor.submit(() -> execute(command, permits, true));
}
```

**执行体**（`CommandWorker.java:182-198`）——关键是 `leases.claim(...)` 是一个**独立短事务**，返回后连接已归还：

```java
lease = leases.claim(command, properties.instanceId(), Instant.now().plus(properties.leaseDuration()))
        .orElse(null);
if (lease == null) return;
// ...
ExecutionContext execution = new ExecutionContext(command.commandId(),
        command.runId(), lease.epoch(), command.deadlineAt(), lease.stateVersion());
ShoppingAgentState result = ExecutionContext.call(execution, () -> invoke(command));   // 这里跑 LLM，不持连接
```

**把约定变成代码约束**（`NetworkCallGuard.java:9-13`）

```java
public static void assertNoTransaction(String dependency) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        throw new IllegalStateException(dependency + " network call attempted inside database transaction");
    }
}
```

这是我最喜欢的一处设计：**不靠 code review 提醒"别在事务里调接口"，而是让它在运行时直接失败。**

### 3.4 实测

`CommandPipelineThroughputIT`：200 条命令 × 20 用户，真实 PostgreSQL + Redis，业务图为替身——

```text
总吞吐      : 878 条/秒
端到端延迟  : p50=185 ms  p95=278 ms  p99=299 ms
```

---

## 4. 租约与栅栏的正确性

### 4.1 难在哪

Worker 崩溃后租约必须能被接管，但接管方要能证明"上一个持有者确实失效了"。判定错了一边，就会出现两个 Worker 同时推进同一个 run。

### 4.2 实现：claim 的短事务与 epoch 递增

`RunLeaseRepository.java:22-73`：

```java
@Transactional
public Optional<Lease> claim(AgentCommand command, String owner, Instant leaseUntil) {
    jdbc.update("""
            INSERT INTO agent_schema.agent_run_execution(run_id) VALUES (?) ON CONFLICT DO NOTHING
            """, command.runId());
    var rows = jdbc.query("""
            SELECT execution_epoch,active_command_id,lease_until,cancel_requested
            FROM agent_schema.agent_run_execution WHERE run_id=? FOR UPDATE
            """, ..., command.runId());
    LeaseRow row = rows.getFirst();
    Instant now = Instant.now();
    if (row.activeCommandId() != null && row.leaseUntil() != null && row.leaseUntil().isAfter(now)) {
        return Optional.empty();                       // 租约仍在有效期内，不抢
    }
    // ... 取消标记处理、上一任恢复 ...
    long epoch = row.epoch() + 1;                      // epoch 单调递增，旧 Worker 的写会被栅栏拒绝
    int updated = jdbc.update("""
            UPDATE agent_schema.agent_run_execution SET execution_epoch=?,active_command_id=?,lease_owner=?,
                lease_until=?,cancel_requested=false,updated_at=now() WHERE run_id=?
            """, epoch, command.commandId(), owner, Timestamp.from(leaseUntil), command.runId());
    int commandUpdated = jdbc.update("""
            UPDATE agent_schema.agent_command SET status='RUNNING',attempts=attempts+1,
                started_at=COALESCE(started_at,now()),execution_epoch=? WHERE command_id=?
                AND (status='QUEUED' OR (status='RETRY_WAIT' AND available_at<=now()))
            """, epoch, command.commandId());
    if (updated != 1 || commandUpdated != 1) throw new ClaimConflict();
    // ...
}
```

三个要点：

1. `FOR UPDATE` 行锁保证同一 run 的 claim 串行
2. `epoch = 旧值 + 1`，**单调递增**，是栅栏令牌
3. 两次 UPDATE 都校验影响行数，不满足直接 `ClaimConflict`——**不静默吞掉**

### 4.3 实现：栅栏如何落地

`ExecutionContext`（`ExecutionContext.java:11-29`）用 `ThreadLocal` 携带当前执行的 epoch 与期望状态版本：

```java
public final class ExecutionContext {
    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();
    private final long epoch;
    private final AtomicLong expectedStateVersion;
    public void stateSaved() { expectedStateVersion.incrementAndGet(); }
}
```

旧 Worker 写回时被拒绝（`CommandWorker.java:209-215`）：

```java
} catch (CommandExceptions.StaleExecution stale) {
    meters.counter("buyforu_fenced_write_rejected_total").increment();
    // 栅栏拒绝意味着该 Worker 已失去写权限，不能让命令继续伪装成 RUNNING。
    commands.markFailed(command.commandId(), "STALE_EXECUTION", "任务已被更新的执行实例接管");
```

### 4.4 实现：续租判据

`CommandWorker.java:128-136`：

```java
HeartbeatOutcome renewLease(UUID commandId, RunLeaseRepository.Lease lease, Instant now) {
    if (leases.cancellationRequested(lease)) return HeartbeatOutcome.STOP;
    // 判据是命令期限而非已运行时长：PLANNING 允许 210 秒，慢模型响应与三级 Replan 必须能跑满该期限。
    Instant deadlineAt = commands.find(commandId).map(AgentCommand::deadlineAt).orElse(null);
    if (!shouldRenewLease(deadlineAt, now)) return HeartbeatOutcome.STOP;
    return leases.heartbeat(lease, now.plus(properties.leaseDuration()))
            ? HeartbeatOutcome.RENEWED
            : HeartbeatOutcome.LEASE_LOST;
}

static boolean shouldRenewLease(Instant deadlineAt, Instant now) {
    return deadlineAt == null || deadlineAt.isAfter(now);
}
```

**这里踩过两个真实缺陷，都是补集成测试时才暴露的（单测测不出来）：**

**缺陷一：90 秒硬阈值误杀合法任务**
原实现用"首次开始时间 + 90 秒"判断是否卡死，但规划命令的合法期限是 210 秒——**所有超过 90 秒的合法任务被确定性误杀**。修复：判据改为命令期限 `deadlineAt`，已运行时长不构成终止理由。

**缺陷二：一条陈旧租约中断整轮心跳**
`cancellationRequested` 原本用 `queryForObject` 查询一个**可能不存在**的行（租约被 `recoverExpired()` 回收后行就没了），空结果抛 `EmptyResultDataAccessException`，异常从 `forEach` 里冒出——**排在后面的所有租约全部错过这一轮续租**。这是跨命令的连带损伤，且每 10 秒重复一次，直到该实例上所有命令集体降级。

修复两处：

```java
// 1) RunLeaseRepository：query + 判空，而不是 queryForObject
var rows = jdbc.query("""
        SELECT cancel_requested FROM agent_schema.agent_run_execution
        WHERE run_id=? AND active_command_id=? AND execution_epoch=?
        """, (rs, row) -> rs.getBoolean(1), lease.runId(), lease.commandId(), lease.epoch());
return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());

// 2) CommandWorker：先收集待摘除 key，遍历结束后统一摘除
List<UUID> lost = new ArrayList<>();
inFlightLeases.forEach((commandId, lease) -> {
    switch (renewLease(commandId, lease, now)) {
        case RENEWED -> { }
        case STOP -> { inFlight.cancel(commandId); /* interrupt worker */ }
        case LEASE_LOST -> lost.add(commandId);
    }
});
lost.forEach(inFlightLeases::remove);
```

第 2 点的注释写明了原因：`forEach` 期间做结构修改能否成立取决于具体 `Map` 实现（`ConcurrentHashMap` 允许，`LinkedHashMap` 抛 `ConcurrentModificationException`），摘除后置让方法对任何 `Map` 都成立。

**沉淀的不变量**：
- I6：心跳循环中任何单条租约的处理失败，都不得影响其余租约在该轮的处理
- I7：读取"可能不存在的行"禁止使用 `queryForObject`

### 4.5 崩溃兜底

`recoverExpired()`（`RunLeaseRepository.java:76-107`）每 5 秒执行，且**分三段兜底**：

1. 正常路径：租约到期的 RUNNING 命令 → `RETRY_WAIT`（或次数/期限用尽 → `EXPIRED`）
2. 清理：清空过期的 `active_command_id`
3. **防御性兜底**：租约行已被清掉/覆盖、但命令仍是 `RUNNING` 的历史数据与异常窗口

第 3 段的注释明确写了它存在的原因："防御'租约行已被清掉/覆盖，但命令仍是 RUNNING'的历史数据与异常窗口"。

---

## 5. Agent 与交易域的边界

### 5.1 难在哪

大模型输出有不确定性，而交易要求确定性；并且合规上资金与交易动作必须能归责到人，不能归责到一次模型推理。

### 5.2 实现

**所有权先验**（`CommandService.java:38-40`）

```java
// 1) 先验主人，再 insert。否则别人对你的 runId 发一条 CANCEL，就能订 SSE、还能打断你的规划。
// START 例外：runId 由 userId+key 算出来，对不上别人的任务。
if (type != CommandType.START) assertRunOwner(runId, userId);
```

注释直接写明了威胁模型——**不是"应该先验"，而是"不先验会被攻击"**。

**LLM 只生成数据，流程由固定图决定**：`PlanSpec` 经 JSON Schema + `PlanSpecValidator` 双重校验；`FixedShoppingGraph` 负责推进。模型决定"买什么"，不决定"怎么买"。

**关闭工具自动回调**（`agent-app/src/main/resources/application.yml:32-33`）

```yaml
spring.ai.mcp.client.toolcallback.enabled: false   # 避免模型自行执行交易写操作
```

**金额只在 Commerce 计算**：`summaryHash = SHA-256(snapshotId, userId, addressId, skuId, quantity, payable, deliveryPromise, reservationId, expiresAt)`（`JdbcCommerceEngine.java:283-285`），审批时校验用户、快照版本、摘要、有效期四项（`:521-541`）。

---

## 6. 多实例限流与公平调度

### 6.1 难在哪

单实例内存限流在多实例部署下**每个实例都按全量放行**，等于没限流。同时，若按简单 FIFO，高频用户会垄断执行槽。

### 6.2 实现：Redis Lua 令牌桶

`RedisAdmissionController.java:18-27`——补充令牌、判断、扣减在**一次原子执行**内完成：

```java
private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
        local now=tonumber(ARGV[1]); local rate=tonumber(ARGV[2]); local burst=tonumber(ARGV[3]);
        local values=redis.call('HMGET',KEYS[1],'tokens','time');
        local tokens=tonumber(values[1]) or burst; local previous=tonumber(values[2]) or now;
        tokens=math.min(burst,tokens+math.max(0,now-previous)*rate);
        if tokens < 1 then
          redis.call('HSET',KEYS[1],'tokens',tokens,'time',now); redis.call('PEXPIRE',KEYS[1],120000); return 0;
        end
        redis.call('HSET',KEYS[1],'tokens',tokens-1,'time',now); redis.call('PEXPIRE',KEYS[1],120000); return 1;
        """, Long.class);
```

三个维度同时扣：用户级、IP 级、全局写（`:57-60`）。

### 6.3 实现：虚拟时间公平队列

`RedisFairQueue.java:25-32` 出队脚本：

```lua
local selected=redis.call('ZRANGE',KEYS[1],0,0,'WITHSCORES'); if #selected==0 then return nil end
local user=selected[1]; local score=tonumber(selected[2]); local listKey=ARGV[1]..user;
local command=redis.call('LPOP',listKey); if not command then redis.call('ZREM',KEYS[1],user); return nil end
redis.call('SREM',KEYS[2],command); redis.call('DECR',KEYS[3]); score=score+1; redis.call('SET',KEYS[4],score);
if redis.call('LLEN',listKey)>0 then redis.call('ZADD',KEYS[1],score,user) else redis.call('ZREM',KEYS[1],user); redis.call('DEL',listKey); end
return command
```

机制：活跃用户放在 zset 里按**虚拟时间**排序，每次出队一个用户的一条命令，然后把该用户的虚拟时间 +1 重新入队——**轮转**，高频用户不能连续占用。用户队列空了才从 zset 移除。

配合每用户执行许可（`RedisFairQueue.java:89-93`）：

```java
/** 同一用户同时只跑一条命令。TTL 覆盖规划上限（210 秒），崩溃后最多堵 240 秒，不做心跳续期。 */
public boolean tryAcquireUser(String userId, UUID commandId) {
    return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("{buyforu}:running:user:" + userId,
            commandId.toString(), Duration.ofSeconds(240)));
}
```

### 6.4 Redis 的定位

**Redis 只做协调，不存事实。** 命令事实在 PostgreSQL，Redis 数据丢失后由 `reconcileRedisIndex()`（`CommandWorker.java:75-88`）每 5 秒从数据库重建。Redis 不可用时：新规划/交易命令 fail-closed 返回 503，**查询与取消仍走 PostgreSQL 控制路径保持可用**。

---

## 7. SSE 断线恢复

### 7.1 难在哪

页面刷新或实例切换后，内存里的 SSE 连接就没了，进度丢失。

### 7.2 实现

`RunEventRepository.java:24-42`：

```java
public RunEvent append(String runId, UUID commandId, String type, Object payload) {
    RunEvent event = jdbc.queryForObject("""
            INSERT INTO agent_schema.agent_run_event(run_id,command_id,event_type,payload)
            VALUES (?,?,?,CAST(? AS jsonb))
            RETURNING event_id,created_at
            """, ...);
    notifier.publish(runId);      // 先落库，再唤醒
    return event;
}

public List<RunEvent> after(String runId, long lastEventId, int limit) {
    return jdbc.query("""
            SELECT event_id,command_id,event_type,payload::text,created_at
            FROM agent_schema.agent_run_event WHERE run_id=? AND event_id>? ORDER BY event_id LIMIT ?
            """, ...);
}
```

类注释一句点破设计：`SSE 的持久化事件日志；断线续传读取此表，而不是依赖易失的 Redis Pub/Sub。`

要点：
- **先写 PostgreSQL，再用 Redis Pub/Sub 唤醒 SSE**——Pub/Sub 丢了也没关系
- `after(runId, lastEventId)` 支持 `Last-Event-ID` 续传
- `event_id` 单调递增，天然是游标

---

## 8. 面试怎么用这份文档

**如果只有三分钟**，讲 1 → 3 → 2：

> "这个项目真正的难点不是接大模型，而是**大模型的不确定性怎么和交易的确定性共存**。我拆成三件事：第一，一致性——三层幂等，防超卖靠行锁加锁内检查、再用数据库 `CHECK` 兜底，另外用预占状态机防库存虚增；第二，性能——把慢调用挡在事务外，用短租约和栅栏推进异步命令，实测 878 条每秒、p95 278 毫秒；第三，恢复——把状态分成能重放的和不能重做的，副作用单独记账。边界上我守一条线：**大模型给建议，交易域给事实，支付能力根本不暴露给 Agent。**"

**如果被追问"你遇到过什么难题"**，讲 §4.4 的两个缺陷，收尾用这句：

> "第一个是逻辑错误，第二个是不变量缺失。我从中总结了一条规则：读取可能不存在的行，禁止用 `queryForObject`。"

**如果被追问"`version` 字段是乐观锁吗"**，按 §1.4 如实回答：

> "不是。`version` 是变更计数，用于审计和排查——UPDATE 的 WHERE 里没有版本条件。并发控制完全由 `FOR UPDATE` 行锁提供；因为行锁已把同一 SKU 的操作串行化，再加版本校验是冗余的。要变成真正的乐观锁，需要在 WHERE 加 `AND version = ?` 并检查影响行数。"

**如果被追问"哪些是你真正想清楚的，哪些是抄的"**，诚实回答：`NetworkCallGuard`、`heartbeatOver` 的先收集后摘除、`claim` 的双重影响行数校验这三处是自己踩出来的；令牌桶、公平队列、Outbox 是成熟模式，我按项目约束做了适配。
