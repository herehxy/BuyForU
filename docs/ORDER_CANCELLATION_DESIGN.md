# 订单取消设计（第二条写路径）

> 状态：**已实施并验证**。实施结果、断言与反向验证记录见第 15 节。
> 关联任务：#32。关联文档：`docs/PROJECT_DESIGN.md`、`docs/CORRECTNESS_REVIEW_FIXES.md`、`docs/CODE_GUIDE.md`。

## 1. 目的

BuyForU 目前只有 `order_create` 一个终态写操作。这意味着**"事务一致性 + 并发治理"这条主线只有一条业务路径能证明**，故障演练在单路径下只能验证"同一操作的重放"，无法验证"两个不同写路径交叉争抢同一资源"。

本设计补第二条写路径：**订单级取消 `PENDING_PAYMENT → CANCELLED`**。

选取消而不选退货的理由：

| | 退货 | 取消 |
| --- | --- | --- |
| 需要的新业务域 | 逆向物流、质检、退款结算 | **无** |
| 依赖的既有能力 | 需退款链路（不存在） | `inventory_release` 已存在 |
| 实施成本 | 高 | 低 |

**取消是唯一不需要新增业务域就能开出第二条写路径的迁移。**

## 2. 不做的事（先划边界）

- **不做支付**：`PAID / FULFILLING / SHIPPED / COMPLETED / REFUND_PENDING / REFUNDED` 保持不接线。
- **不做超时自动取消**：那需要一个新后台作业，且引入"订单在自己不知情时消失"的语义。本设计只做**用户显式取消**。
- **不做部分取消**：订单只含单一 SKU（现状即如此），无需拆分。
- **不改动 run 级取消语义**：见第 9 节。

## 3. 现状核实（设计的前提）

| 事实 | 证据 |
| --- | --- |
| `OrderStatus` 定义 8 个状态 | `CommerceModels.java:234-237` |
| 后端**只写入过** `PENDING_PAYMENT` | `JdbcCommerceEngine.java:372`、`InMemoryCommerceEngine.java:201` |
| 其余 7 个状态**从未被赋值**（死枚举） | 全仓检索 `OrderStatus\.` 仅命中上述两处 |
| 前端**已预留**全部状态文案与样式，含 `CANCELLED: 'closed'` | `web/src/types.ts:82-88`、`web/src/components/OrdersPage.tsx:23-29` |
| `orders.status` 是 `VARCHAR(32)`，**无 CHECK 约束** | `commerce-service/.../V1__commerce_core.sql:44-53` |
| 订单创建后停留在 `PENDING_PAYMENT` | `docs/PROJECT_DESIGN.md:204`、`:859` |
| run 级取消不得伪造订单回滚 | `docs/PROJECT_DESIGN.md:550` |

**结论**：状态机是"画好了但没接线"——枚举和前端都到位，后端只有起点。这一点与 RAG 那次"接了线但没通电"恰好方向相反，但同属"看起来存在、实际不生效"。

## 4. 终态定义

### 4.1 订单状态机（本设计只新增一条边）

```
                 ┌──────────────────────────┐
                 │                          │
   [创建] ──► PENDING_PAYMENT ──[取消]──► CANCELLED  (终态/吸收态)
                 │       ▲
                 └───────┘  无出边的自环 = 驻留（状态不变）
```

- `PENDING_PAYMENT` 是**唯一非终态**。
- `CANCELLED` 是**终态（吸收态）**：不允许任何迁出。
- 由于不接支付，`PENDING_PAYMENT` 只有两条出路：**驻留**，或用户显式取消。
- `PAID / FULFILLING / SHIPPED / COMPLETED / REFUND_PENDING / REFUNDED` 保留在枚举中，**不实现任何入边**，并在文档与数据库约束中标注为"未接线"。

### 4.2 为什么终态必须是吸收态

这是本设计唯一新增的"不可逆"迁移。此前所有状态迁移（预占 `ACTIVE → CONSUMED/RELEASED/EXPIRED`）都是单向但**没有反方向守卫**。取消引入了真正的终态语义，因此必须显式断言：**已 `CANCELLED` 的订单不可再迁移**。

实现上不靠代码里的 `if`，而靠**条件更新**（见第 7 节）：所有迁移 SQL 都带 `WHERE status = <期望源状态>`，源状态不匹配则命中 0 行——**数据库层拒绝，应用层无法绕过**。

### 4.3 预占状态机（需扩展）

现有迁移（`JdbcCommerceEngine`）：

```
ACTIVE ──[下单]───► CONSUMED
ACTIVE ──[释放]───► RELEASED     (releaseReservation)
ACTIVE ──[超时]───► EXPIRED      (expireReservations)
```

**必须新增**：

```
CONSUMED ──[订单取消]──► RELEASED_BY_CANCEL
```

**为什么必须新增而不是复用 `RELEASED`**：

1. 业务含义不同：`RELEASED` = "未成交就释放"，`RELEASED_BY_CANCEL` = "成交后撤销回补"。对账与审计必须能区分。
2. 指标需要区分：撤销率 ≠ 释放率。
3. **技术上不可复用**：`releaseReservation` 只对 `ACTIVE` 生效（`JdbcCommerceEngine.java:321`），订单的预占是 `CONSUMED`，**对该方法完全无效**。这是最容易踩的坑——**不能靠调用 `releaseReservation` 实现订单取消**。

**为什么 `CONSUMED` 也是准终态**：`CONSUMED` 只能迁向 `RELEASED_BY_CANCEL`，不能回到 `ACTIVE`。避免"订单取消了但预占被重新激活"导致同一份库存在订单与预占中双重存在。

## 5. 幂等键方案

### 5.1 现有方案（沿用，不发明新的）

```java
// ShoppingWorkflowService.java:540-543
private String effectId(ShoppingAgentState state, String nodeId, int logicalAttempt) {
    return hash(state.runId(), nodeId, String.valueOf(state.planVersion()), String.valueOf(logicalAttempt));
}
// :535-538  idempotencyKey = effectId
```

即 `effectId = SHA256(runId ‖ nodeId ‖ planVersion ‖ logicalAttempt)`，且 `idempotencyKey = effectId`。

**这套方案与 LLM 输出完全解耦**——这是正确性的关键，因为幂等重放要求同一请求产生同一结果，而任何 LLM 参与派生的键都会随记忆/采样漂移。**本设计不改这一点。**

### 5.2 取消路径的必要偏离：锚在「属主 + 订单」上

取消操作的 effect 身份**不能**沿用"以 runId 为锚"的派生：

```
cancelEffectId = SHA256("order-cancel" ‖ userId ‖ orderId)
```

**理由**：

1. **聚合边界不同**。订单是长期存在的业务实体，run 是短期编排会话。取消属于订单聚合。
2. **取消可能没有 run**。用户在**订单页**点"取消订单"时，产生该订单的 run 很可能已 `COMPLETED` 且不在会话上下文中。
3. **以 runId 为锚会造成重复回补**。同一个订单若通过两个不同 run 发起取消，会派生出两个不同的 `effectId` → effect ledger 挡不住 → 库存被回补两次。**这是引入静默数据损坏的路径**。

这是本设计对现有派生规则的**唯一必要偏离**，必须在此显式记录，避免后续被"统一"掉。
实施时另加了一条细化（原始设计只写了 `orderId`）：

> **effectId 完整覆盖请求身份 `(userId, orderId)`，与交易侧 `requestHash` 的口径一致。**
>
> 若 effectId 只认 `orderId`，非属主发起的取消会先撞上账本"同键不同请求"的检查、返回
> `EFFECT_CONFLICT`，而不是本应给出的 `ORDER_USER_MISMATCH`——**报错语义错了，排查方向就会被带偏**。
> 两个键都只决定"报什么错"，不决定能否重复回补：重复回补由第 6 节两道状态条件更新兜底，
> 因此把 userId 纳入 effectId 不会削弱任何一致性保证（一笔订单只有唯一属主，属主恒定则键恒定）。

派生入口收敛在一处：`OrderCancellationService.effectId(userId, orderId)`，
其返回值被 `OrderCancellationServiceTest` 用字面量哈希锁死。

### 5.3 `cancelAttempt` 的含义

**实施结论：不引入 `cancelAttempt`。** 原始设计为它留了位置，但实际落地时发现它是多余的：
崩溃恢复重试必须沿用同一 `effectId`（否则 ledger 失去意义），而"业务上确实是一次新取消请求"
在同一订单上不可能发生——订单一旦 `CANCELLED` 就是终态，"再取消一次"永远是重放而非新请求。
留一个只能取 0 的参数只会给后来者留下误用空间。

`EffectContext.attempt` 仍固定传 0；需要区分重试语义时由命令队列（`agent_command`）承担，与订单聚合无关。

## 6. 权威写入流程

新增 `CommerceGateway.cancelOrder(CancelOrderCommand, EffectContext)`。全部逻辑在**一个事务**内：

```
1.  assertEffectUser(effect, command.userId)          // 防越权
2.  beginEffect(effect, "CANCEL_ORDER", requestHash)  // ① effect ledger 栅栏
      - 已 COMPLETED → 直接返回记录的结果（重放，无副作用）
      - PENDING      → 抛 EFFECT_IN_PROGRESS（不并发执行）
3.  SELECT order_payload FROM orders
      WHERE order_id = ? FOR UPDATE                   // ② 行级锁（栅栏）
4.  校验 order.userId == command.userId               // 防越权读他人订单
5.  UPDATE orders SET status='CANCELLED', version = version + 1
      WHERE order_id = ? AND status = 'PENDING_PAYMENT'   // ③ 核心栅栏
      rows = 影响行数
6.  IF rows == 0:                                     // 幂等/非法迁移
      读回当前订单并 completeEffect 后返回
      （不抛异常、不回补库存、不改任何数量）
7.  SELECT sku_id, quantity, status FROM inventory_reservation
      WHERE reservation_id = ? FOR UPDATE              // ④ 行级锁
8.  UPDATE inventory_reservation SET status='RELEASED_BY_CANCEL'
      WHERE reservation_id = ? AND status = 'CONSUMED'     // ⑤ 第二道栅栏
      IF 影响行数 == 1:                                // 只有这次迁移是我赢的
9.        UPDATE inventory SET available_quantity = available_quantity + ?,
                                version = version + 1
          WHERE sku_id = ?                             // ⑥ 回补，与状态同事务
10. INSERT INTO outbox_event (..., event_type='ORDER_CANCELLED', ...)  // ⑦ 同事务
11. completeEffect(effect, orderId, cancelledOrder)
```

**第 5 步与第 8 步是两道独立的栅栏，这是本设计的核心**：

- 第 5 步防"重复取消"（订单维度）
- 第 8 步防"重复回补"（预占维度）

即使第 5 步的幂等键因任何原因算错（例如被错误地改回以 runId 派生），第 8 步仍能挡住重复回补。**这是纵深防御**，也是与现有 `expireReservations` 的同构做法（`JdbcCommerceEngine.java:510-513` 用 `WHERE ... AND status='ACTIVE'` 保证回收只生效一次）。

## 7. 栅栏清单与锁顺序

### 7.1 栅栏位置

| # | 栅栏 | 位置 | 挡住什么 |
| --- | --- | --- | --- |
| 1 | `pg_advisory_xact_lock(hashtextextended(idempotencyKey))` | `beginEffect` 入口 | 同一 effect 的并发重试 |
| 2 | `SELECT ... FOR UPDATE` on `orders` | 流程 3 | 同一订单的并发取消 |
| 3 | **条件更新 `WHERE status='PENDING_PAYMENT'`** | 流程 5 | **重复取消、非法迁移（终态不可逆）** |
| 4 | `SELECT ... FOR UPDATE` on `inventory_reservation` | 流程 7 | 与后台过期回收争抢同一预占行 |
| 5 | **条件更新 `WHERE status='CONSUMED'`** | 流程 8 | **重复回补** |
| 6 | 回补 SQL 与状态更新**同事务** | 流程 8-9 | 库存与状态不一致 |
| 7 | Outbox **同事务**写入 | 流程 10 | 订单已取消但事件永久丢失 |

### 7.2 锁顺序（必须固定）

**规定顺序：`orders` → `inventory_reservation` → `inventory`**

一致性检查（已逐一核对现有代码）：

| 现有路径 | 锁顺序 | 是否一致 |
| --- | --- | --- |
| `createOrder` | snapshot → reservation(过期回收) → **orders** → **inventory_reservation** → inventory | ✅ orders 先于 reservation |
| `releaseReservation` | **inventory_reservation** → inventory | ✅ |
| `prepareConfirmableOrder` | **inventory** → insert reservation | ✅ 只 INSERT 新行，不与其他行锁冲突 |
| **`cancelOrder`（本设计）** | **orders** → **inventory_reservation** → inventory | ✅ 与上表兼容 |

**必须加死锁回归测试**（见 11.4），因为锁顺序是**约定而非编译器保证**，任何一次重构都可能破坏它而只在生产高并发下暴露。

## 8. 结果未知窗口

沿用现有原则：**绝不重放写操作来"确认"结果**（`ShoppingWorkflowService.java:323` 注释明确）。

取消的未知窗口（调用超时，不知是否成功）按两条路径处理：

1. **同一 effect 上下文内**：用**同一个 `effectId` 重放** `cancelOrder`。effect ledger 命中 `COMPLETED` 则直接返回记录结果。这是安全的，因为幂等键确定性派生。
2. **跨 run / 跨进程（无 effect 上下文）**：**只读查询订单状态**（`listOrders` / 新增的按 orderId 查询），`status == CANCELLED` 即已成功。**不得**为了"确认"而重放写。

需要在 Agent 侧复用现有模式：`findOrderBySnapshot` 就是为这个窗口存在的，取消路径需要一个对等的只读查询能力。

## 9. 与 run 级取消的边界（不可混淆）

现有原则（`docs/PROJECT_DESIGN.md:550`）：

> 已经创建订单时，Agent 恢复为 `COMPLETED`，不会伪造回滚或把订单隐藏成 `CANCELLED`。

本设计**继承**该原则，并明确三条边界：

| # | 规则 |
| --- | --- |
| 1 | **run 级取消 ≠ 订单取消**。二者是不同聚合上的独立操作，`Phase.CANCELLED` 与 `OrderStatus.CANCELLED` 语义不同，不可互推。 |
| 2 | **订单取消不得由 run 取消隐式触发**。用户必须在订单页显式发起。run 取消最多只能"释放尚未成交的预占"。 |
| 3 | **订单取消成功后，原 run 保持 `COMPLETED`**，不修改历史 run。订单页显示的 `CANCELLED` 来自**订单读取路径**（`CommerceGateway.listOrders`），而非 run 回放——这与该方法的既有注释一致。 |

## 10. 数据库迁移

新增 `commerce-service/src/main/resources/db/migration/V10__order_cancellation.sql`：

```sql
-- 把订单状态机写进数据库：此前 status 是裸 VARCHAR(32)，状态机只存在于应用层约定里。
ALTER TABLE commerce_schema.orders
    ADD CONSTRAINT orders_status_known CHECK (
        status IN ('PENDING_PAYMENT','PAID','FULFILLING','SHIPPED','COMPLETED',
                   'CANCELLED','REFUND_PENDING','REFUNDED')
    );

-- 取消时间：审计与"取消耗时"指标需要，且与 created_at 一起支撑守恒核对。
ALTER TABLE commerce_schema.orders ADD COLUMN cancelled_at TIMESTAMPTZ;

-- 取消幂等锚在 order_id 上，需要能按订单快速定位预占。
CREATE INDEX IF NOT EXISTS idx_orders_status ON commerce_schema.orders (status);
```

**注**：现有数据全部是 `PENDING_PAYMENT`，加 CHECK 约束不会失败。CHECK 只枚举"合法状态名"，不表达合法迁移——迁移合法性仍由条件更新保证。

## 11. 断言清单与实际落点

这是本设计最重要的部分。项目哲学是**可证伪**：每条断言都必须能通过"临时破坏实现"变红，否则它证明不了任何事。

断言分三层，**分层本身就是结论**——替身证明不了真实行锁，单点断言证明不了守恒律：

| 层 | 文件 | 能证明什么 | 证明不了什么 |
| --- | --- | --- | --- |
| 逻辑栅栏（替身） | `commerce-service/src/test/.../application/OrderCancellationTest.java`（12 条） | 幂等键锚点、状态条件更新、预占终态区分、越权拒绝 | 真实行锁与并发（替身方法 `synchronized`，天然串行） |
| 真实事务（Postgres） | `commerce-service/src/test/.../it/OrderCancellationIT.java`（15 条） | 行锁、并发只回补一次、库存守恒律、原子回滚、锁顺序无死锁、迁移生效 | 编排层如何派生幂等键 |
| 用例层 | `agent-app/src/test/.../application/OrderCancellationServiceTest.java`（5 条） | effectId 只由 `(userId, orderId)` 决定，并用字面量哈希锁死 | 交易侧的任何行为 |

**IT 必须把引擎调用包在真实事务里**（`TransactionTemplate`）。`JdbcCommerceEngine` 由测试手工构造，
`@Transactional` 没有代理、不生效；若让语句跑在自动提交下，`pg_advisory_xact_lock` 在语句结束瞬间释放、
`FOR UPDATE` 形同不存在——**并发用例会给出"看起来通过"的假绿**。

### 11.1 幂等

| 断言（实际方法名） | 落点与要点 |
| --- | --- |
| `cancelReleasesStockExactlyOnce` | 替身 + IT：可用量精确 `+N`、订单版本推进、`cancelled_at` 落库 |
| `repeatedCancelWithSameEffectReplaysRecordedResult` | 替身 + IT：同一 `EffectContext` 重放，版本不变、库存不变 |
| `cancelOfCancelledOrderReturnsRecordedResult` | 替身 + IT：不抛异常、不回补、版本不再推进 |
| `cancelWithUnrelatedEffectIdDoesNotReleaseAgain` | 替身 + IT：**§5.2 的核心断言**——换一个全新幂等键再取消，库存不得再变，且取消事件只有一条 |

### 11.2 并发

| 断言 | 落点与要点 |
| --- | --- |
| `concurrentCancelWithDistinctEffectsReleasesExactlyOnce` | IT：两线程各持真实事务、**各持不同幂等键**（不共享 advisory lock），只能靠 `orders` 行锁串行化 |
| `concurrentCancelWithDistinctEffectsReleasesExactlyOnce`（替身版） | 8 线程并发，全部收敛到 `CANCELLED` 且只回补一次 |

### 11.3 守恒律（最强证据）

**`conserved(sku)`**：`可用 + ACTIVE 未过期预占 + 未取消订单的 CONSUMED 预占 == 初始库存`。
`RELEASED_BY_CANCEL` / `RELEASED` 的预占**不参与求和**——它们的库存已经回到可用量里，再加一次就是重复计数。

| 断言 | 落点与要点 |
| --- | --- |
| `inventoryConservationInvariantHoldsAcrossCreateAndCancel` | IT：下单后 / 取消后 / 两次重复取消后，守恒都必须成立（数量 2 与 3） |
| `inventoryIsConservedAcrossCreateAndCancel` | 替身版守恒 |
| `cancelReleasesOnlyItsOwnReservation` | 替身 + IT：取消甲订单不影响乙订单的预占与状态 |

### 11.4 锁与死锁

| 断言 | 落点与要点 |
| --- | --- |
| `concurrentCreateAndCancelDoNotDeadlock` | IT：5 轮 `create × cancel` 交叉并发（`CyclicBarrier` 同时起跑），结束后仍断言守恒 |

锁顺序是**约定而非编译器保证**，所以用并发交叉间接证明：顺序反转会以 40P01（deadlock detected）冒出来。

### 11.5 越权与安全

| 断言 | 落点与要点 |
| --- | --- |
| `cancelRejectsOtherUsersOrder` | 替身 + IT：`ORDER_USER_MISMATCH`，且订单状态与库存零副作用 |
| `cancelRequiresEffectUserToMatchCommandUser` | 替身 + IT：`EFFECT_USER_MISMATCH` |
| `cancelRejectsUnknownOrder` | 替身 + IT：`ORDER_NOT_FOUND` |

### 11.6 状态机与迁移

| 断言 | 落点与要点 |
| --- | --- |
| `cancelledOrderStaysAbsorbingUnderRepeatedAttempts` | 替身：5 次重复取消，版本不变 |
| `cancelledReservationIsDistinguishableFromPlainRelease` | 替身 + IT：`RELEASED` vs `RELEASED_BY_CANCEL` |
| `cancelRefusesOrderThatMovedBeyondPendingPayment` | IT：手工把订单推到 `PAID`，取消抛 `ORDER_NOT_CANCELLABLE` 且**原子回滚**（为日后接支付预留的守卫） |
| `databaseRejectsUnknownOrderStatus` | IT：直接验证 V10 的 CHECK 约束真的生效，而不是只躺在文件里 |
| `failedCancelRollsBackOrderStatusLedgerAndOutboxTogether` | IT：**"一个事务"这句话的唯一证据**——失败时订单状态、effect ledger、Outbox 一起消失 |

### 11.7 反向验证结果（已真实执行）

每条核心断言都用"临时破坏实现 → 断言必须变红 → 还原"证明它不是恒绿的。
**下表是实际跑出来的结果，不是预期值。**

| # | 破坏 | 变红的断言（实测） | 结论 |
| --- | --- | --- | --- |
| 1 | 去掉订单侧 `AND status='PENDING_PAYMENT'` | IT 6 条：`cancelOfCancelledOrder*`、`cancelWithUnrelatedEffectId*`、`inventoryConservation*`、`concurrentCancel*`、`concurrentCreateAndCancel*`、`cancelRefusesOrderThatMoved*` | **第一道栅栏承重**；失败形态是第二道栅栏抛 `RESERVATION_STATE_INCONSISTENT`，说明**纵深防御真的接住了**，没有变成静默损坏 |
| 2a | 去掉显式 `CONSUMED` 预检（保留第二道栅栏） | **全绿（15/15）** | 第二道栅栏独立成立；显式预检是冗余但**可读性有价值**的防御 |
| 2b | 再去掉预占侧 `AND status='CONSUMED'` | IT 1 条：`failedCancelRollsBackOrderStatusLedgerAndOutboxTogether` | 该断言的守卫**正是**第二道栅栏，且归因干净（其余 14 条保持绿） |
| 3 | 不写 Outbox 事件 | IT 2 条：`cancelWithUnrelatedEffectId*`、`concurrentCancel*`（失败于"取消事件只能有一条：expected 1 but was 0"） | 事件计数断言非恒绿 |
| 4 | effectId 混入每次调用都不同的成分 | 用例层 2 条：`effectIdIsPureFunctionOfOwnerAndOrder`、`sameOwnerAndOrderAlwaysProduceSameEffectId` | **§5.2 的回归锁生效**：改回 runId/随机派生会立刻变红 |
| 5 | 去掉 `assertEffectUser` | IT 1 条：`cancelRequiresEffectUserToMatchCommandUser`（`EFFECT_USER_MISMATCH` → `ORDER_USER_MISMATCH`） | 越权断言非恒绿 |
| 6 | 回补数量写死为 `+1` | IT 3 条红，而 `cancelReleasesStockExactlyOnce`（数量 1）**保持绿** | **守恒律的价值被量化**：单点断言（数量恰好为 1）对此完全失明，守恒律抓住了它 |
| 7 | 让替身不再吸收 `CANCELLED` | 替身 4 条红 | 替身侧的终态断言同样非恒绿 |

**实验 6 是本设计最有价值的产出**：它把第 13 节"全局不变量比单点断言更强"从修辞变成了实测数字。

### 11.8 原始设计中被证伪的两条破坏方式

原 §11.7 里有两行"破坏方式"实测**不成立**，已由上表替换，记录于此以免后人重走：

- ~~"把第 9 步移到第 8 步之前 → 守恒律变红"~~：两条语句在**同一事务**内，顺序不影响提交结果，外部也观察不到中间态，因此不会有任何断言变红。
- ~~"去掉第 8 步的 `WHERE status='CONSUMED'` → 并发取消断言变红"~~：并发取消时后到者会阻塞在 `orders` 行锁上，提交后读到 `CANCELLED` 便提前返回，**根本走不到第 8 步**。真正会被它守住的是"预占状态被外部破坏"这类场景（见实验 2b）。

## 12. 改动文件清单（实际）

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `commerce-port/.../CommerceModels.java` | 新增 `CancelOrderCommand(orderId, userId)`；`ReservationStatus` 新增 `RELEASED_BY_CANCEL` |
| 2 | `commerce-port/.../CommerceGateway.java` | 新增 `Order cancelOrder(CancelOrderCommand, EffectContext)`（含语义 javadoc） |
| 3 | `commerce-service/.../JdbcCommerceEngine.java` | 实现 `cancelOrder`（第 6 节流程） |
| 4 | `commerce-service/.../CommerceMcpTools.java` | 新增 `commerce_order_cancel` 工具（签名与 `commerce_order_create` 对齐） |
| 5 | `commerce-service/.../db/migration/V10__order_cancellation.sql` | 见第 10 节 |
| 6 | `commerce-service/.../test/.../InMemoryCommerceEngine.java` | **同步实现** + 补齐 `assertEffectUser` + 只读测试钩子 |
| 7 | `agent-app/.../infrastructure/commerce/`（3 处） | `CommerceToolClient` / `McpCommerceGatewayAdapter` / `SpringMcpCommerceToolClient` 透传取消 |
| 8 | `agent-app/.../api/OrderController.java` | 新增 `POST /api/v1/orders/{orderId}/cancellations`（**刻意不接收客户端幂等键**） |
| 9 | `agent-app/.../application/OrderCancellationService.java`（新增） | 取消用例：绕开购物图与命令队列，直接面向订单聚合 |
| 10 | `web/src/api.ts` + `components/OrdersPage.tsx` + `App.tsx` + `styles.css` | 取消按钮（`CANCELLED: 'closed'` 样式**已存在**）、二次确认、危险色、成功后重读订单与库存 |
| 11 | `commerce-service/.../test/.../OrderCancellationTest.java`（新增，12 条） | 替身层断言 |
| 12 | `commerce-service/.../test/.../it/OrderCancellationIT.java`（新增，15 条） | 真实事务层断言 |
| 13 | `agent-app/.../test/.../OrderCancellationServiceTest.java`（新增，5 条） | 幂等键派生规则的字面量锁 |
| 14 | `docs/ORDER_CANCELLATION_DESIGN.md`（本文件） | 设计与实施记录 |

相对原始设计的偏离：`CancelOrderCommand` **去掉了 `reason` 自由文本**（它会把每次重试变成不同请求，
直接触发 `EFFECT_CONFLICT`；取消原因属于审计字段，不该进业务幂等键）；**没有单独改动 `EffectLedger`**
（取消的操作类型在既有 `operation` 维度内即可表达）。

**注意 #6**：测试替身必须同步。任务 #2 的教训是替身与生产语义不一致会制造假绿——本次实施中
该教训**真实复现**：替身当时没有 `assertEffectUser`，"越权取消"这条断言在替身上恒绿，
与生产的 `EFFECT_USER_MISMATCH` 分叉。**替身一旦比生产宽松，它就是假绿的温床。**

## 13. 本设计新增的一致性证据

| 证据 | 此前 | 此后 |
| --- | --- | --- |
| 跨聚合一致性 | 无（只有单聚合迁移） | 订单状态与库存数量必须同事务一致 |
| 逆向操作幂等 | 无（只有正向扣减） | 回补幂等 |
| 终态不可逆 | 无 | 状态机首次出现吸收态 |
| 多路径交叉 | 无（单写路径） | create × cancel 可交叉，故障演练能杀在两者之间 |
| 全局不变量 | 无 | 库存守恒律可断言 |

**这正是本设计的全部价值**：它不是为了"多一个功能"，而是为了把一致性证明从"单点"提升到"交叉 + 不变量"。

## 14. 已知风险

| 风险 | 等级 | 缓解 | 实施后状态 |
| --- | --- | --- | --- |
| 锁顺序被后续重构破坏 → 死锁 | 中 | 第 11.4 节死锁回归测试；第 7.2 节显式记录约定 | 已覆盖（5 轮交叉并发，实测无 40P01） |
| 幂等键被"统一"回 runId 派生 → 重复回补 | 中 | 第 5.2 节记录偏离理由；字面量哈希锁；第二道栅栏兜底 | 已覆盖（实验 4 证明回归锁会变红） |
| 测试替身未同步 → 假绿 | 中 | 第 12 节 #6 列为必改项 | **已实际发生并修复**：替身缺 `assertEffectUser` |
| 取消使"订单永存"假设失效 | 低 | 订单是软取消（状态迁移），不删除行，审计链完整 | 已覆盖（`cancelled_at` 落库，无 DELETE 路径） |
| 前端误把 run 取消当订单取消 | 低 | 第 9 节三条边界；两个入口在 UI 上必须文案区分 | 已覆盖（按钮文案"撤销订单"、二次确认文案说明只释放预占） |
| 未接线状态被误认为可用 | 低 | V10 的 CHECK 放行 5 个未接线状态，但取消显式拒绝非 `PENDING_PAYMENT` | 已覆盖（`cancelRefusesOrderThatMovedBeyondPendingPayment`） |

## 15. 实施记录

| 项 | 结果 |
| --- | --- |
| 迁移 | V10 在本机 Testcontainers 的 Postgres 17 上干净应用（`Successfully applied 10 migrations ... now at version v10`） |
| 编译 | `commerce-port` / `commerce-service` / `agent-app` 三模块 `test-compile` 全绿（`CommerceGateway` 的 4 个实现全部同步，无 `NoSuchMethodError` 风险） |
| 单元测试 | commerce-service 29 条、agent-app 150 条，全部绿 |
| 集成测试 | `OrderCancellationIT` 15 条 + 既有 `BudgetSnapshotIT` 3 条 + `OutboxDispatchIT` 2 条，全部绿 |
| 反向验证 | 7 组破坏实验全部执行并还原，结果见 §11.7；还原后全量重跑复绿 |
| 前端 | `tsc --noEmit` 通过；取消按钮只在 `PENDING_PAYMENT` 出现，点击需二次确认，成功后同时重读订单与库存 |

**尚未完成（明确记录，不留悬空）**：

1. **`*IT` 类不会被 `mvn test` / `mvn verify` 执行**——项目没有配置 failsafe，`*IT` 不在 surefire 默认包含范围内，
   目前只能显式 `-Dtest=OrderCancellationIT` 手动跑。这是任务 #20（定 CI 策略）的一部分：
   **不修这一点，"有集成测试"就是一句需要人工记忆才能成立的话。**
2. 文档收口（`PROJECT_DESIGN.md` / `FILE_INDEX.md` / `CODE_GUIDE.md` 同步本节内容）并入任务 #20。
3. 本地 `commerce-service` 尚需重启一次以应用 V10（重启前 `orders.status` 无 CHECK 约束）。
