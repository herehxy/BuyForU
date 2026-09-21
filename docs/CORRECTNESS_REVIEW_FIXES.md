# BuyForU 需求演进、Review 与修复记录

> 这份文档记录“为什么这样实现、Review 发现了什么、代码如何修复、如何验证”。
> 总体架构、API、数据模型见 [PROJECT_DESIGN.md](PROJECT_DESIGN.md)。
>
> 基线：`fix/correctness-and-acceptance` 当前工作区，日期：2026-08-14。

## 1. 需求演进

### 1.1 初始产品需求

项目最初要解决的是一个真实电商购物闭环，而不是只生成推荐文本：

```text
登录
  → 输入自然语言购物需求
  → Agent 规划与澄清
  → 实时商品搜索
  → 用户选择 SKU
  → Commerce 权威报价和库存预占
  → 用户确认快照
  → 幂等创建订单
```

关键业务约束：

- Agent 负责理解、规划、检索和排序，不负责金额、优惠、库存或订单事实。
- 订单创建必须经过用户对当前 `ConfirmableOrderSnapshot` 的明确批准。
- 价格、优惠、运费和库存必须在 Commerce 内重新校验。
- 任何写副作用都必须可幂等恢复，不能因为网络超时或进程崩溃重复预占、重复下单。
- 没有结果时可以换候选或重搜，但不能由模型偷偷放宽用户硬约束。

### 1.2 架构设计需求

在参考 ragent 和原始 BuyForU 项目的工程思路后，设计冻结为以下边界：

| 需求 | 设计决策 |
| --- | --- |
| LLM 不得任意执行交易 | LLM 只生成结构化 `PlanSpec`，LangGraph4j 使用 Java 固定图 |
| MCP 不得污染业务层 | 业务层依赖纯 Java `CommerceGateway`，MCP 只是 Adapter |
| RAG 不得冒充交易事实 | pgvector 只提供规则/知识证据；实时价格和库存必须查询 Commerce |
| 交易需要强一致 | PostgreSQL、事务、行锁、effect ledger 和 Outbox 位于 Commerce |
| 规划请求可能耗时 | 写接口持久化为命令，返回 `202 Accepted`，Worker 异步推进 |
| 多实例不能互相覆盖 | Run lease、execution epoch 和 state version 共同 fencing |
| Redis 不能成为事实库 | Redis 只保存限流、公平队列索引和通知；命令事实在 PostgreSQL |

## 2. 分阶段实现记录

### 阶段 A：交易域与 Agent 主链

实现了 `commerce-port`、`commerce-service` 和 `agent-app` 三个 Java 模块：

- `CommerceGateway` 隔离 Agent 与交易实现。
- `JdbcCommerceEngine` 负责商品、报价、促销、运费、库存、预占、订单和 Outbox。
- `PlanSpec`、`ShoppingAgentState` 和 `FixedShoppingGraph` 组成固定 Agent 流程。
- `ConfirmableOrderSnapshot` 绑定报价、履约、库存预占、摘要和有效期。
- `ApprovalProof` 防止确认页面内容与真实下单条件不一致。

### 阶段 B：AI、RAG、MCP 与 Web

- Spring AI 通过 OpenAI 兼容接口调用 DeepSeek，生成结构化 `PlanSpec`。
- Ollama Embedding + pgvector 保存和召回知识片段。
- Spring AI MCP Client/Server 负责 Agent 与 Commerce 的结构化通信。
- Keycloak OIDC Authorization Code + PKCE 负责登录；API 只信任 JWT `sub`。
- React 使用认证 `fetch + ReadableStream` 消费 SSE，并在断线后轮询命令状态。

### 阶段 C：高并发治理

- Redis Lua Token Bucket：用户、IP 和全局入口限流。
- Redis 用户级虚拟时间队列：用户内 FIFO，用户间等权轮转。
- PostgreSQL `agent_command`：持久化命令、幂等键、期限和公开错误状态。
- `agent_run_execution`：短事务 claim、30 秒租约、取消标记和 execution epoch。
- 独立虚拟线程执行器与 Bulkhead：规划、交易、控制、DeepSeek、MCP Read/Write、Embedding 隔离。
- `NetworkCallGuard`：禁止在数据库事务内调用 DeepSeek、Ollama、MCP 或 Webhook。

### 阶段 D：全链路 Review 与回归

Review 不只检查正常路径，还模拟了：

- 快照过期、重新报价和中间崩溃。
- Worker 租约过期、旧 Worker 恢复和多实例竞争。
- 同一幂等键并发提交。
- MCP Schema 校验失败、传输失败和 Graph 异常包装。
- 预占/下单响应丢失后取消或恢复。
- SSE 断线、页面刷新和代理缓冲。

## 3. Review 问题与当前修复

### 3.1 已关闭的问题

| 问题 | 原因 | 当前实现 |
| --- | --- | --- |
| `budgetMin` 没有进入最终交易校验 | 搜索阶段校验不等于成交阶段校验 | `PrepareOrderRequest`、MCP DTO、requestHash 和 Commerce 快照事务统一校验上下限 |
| 预算上下限方向错误 | `budgetMin > budgetMax` 会产生不可解释的空结果 | `PlanSpecValidator` 在规划后直接拒绝 |
| 快照过期仍复用旧 effectId | effect ledger 会永远返回过期快照 | 释放旧预占、递增 `planVersion`、生成新的 prepare effect |
| 旧租约被新 Worker 覆盖 | 旧命令可能永久停在 RUNNING | claim 前恢复过期 active command，恢复器扫描孤儿 RUNNING |
| 旧 Worker 恢复后覆盖新状态 | 仅靠租约无法 fencing | 所有状态/checkpoint 保存验证 run、command、epoch 和 state version |
| 取消排队中的 START | run 还没有业务状态 | 取消同 run 的 QUEUED/RETRY_WAIT 命令；无 run 时直接完成取消命令 |
| Redis 入队失败返回 500 | 客户端无法判断是协调层故障 | 统一返回 `503 COORDINATION_UNAVAILABLE`，查询和取消仍可用 |
| 商品搜索促销 N+1 | 每个 SKU 单独查询规则 | 每次搜索批量加载有效规则，最终 prepare 仍重新权威报价 |
| 搜索过滤与排序属性名不一致 | `memoryGB` 和目录 `memory` 产生不同结果 | `CatalogAttributeNormalizer` 上收到 `commerce-port`，搜索和排序共用 |
| MCP isError 不进入熔断统计 | 错误判断发生在保护范围外 | 在 `DependencyExecutor` 保护的 Callable 内分类领域错误、契约错误和基础设施错误 |
| LangGraph 包装后 MCP 分类丢失 | 只检查最外层异常 | `CommandWorker.classify` 沿 cause chain 查找 Commerce/MCP/Timeout 类型 |
| MCP Schema 原文返回浏览器 | MCP 契约异常继承 `IllegalArgumentException` | API 和命令状态使用固定公开文案；原始细节只写服务日志 |
| SSE 进度只依赖内存连接 | 刷新或实例切换会丢进度 | 事件先写 PostgreSQL，再用 Redis Pub/Sub 唤醒 SSE；前端断线轮询 |
| `Last-Event-ID` 固定为 0 | 重连不能继续上次游标 | 浏览器按 command 保存并提交事件 ID；完成后清理 |
| HTTP DTO 无边界 | 超大集合和未知字段可以进入业务 | 独立 HTTP 输入模型、长度/数量限制、放宽字段白名单 |
| Flyway 集成测试冲突 | Agent 扫描到 Commerce 的同版本迁移 | Agent 测试固定使用自身 migration 目录 |
| 长时间规划命令被 90 秒硬阈值误杀 | heartbeat 用命令“首次开始时间”判定卡住，与 PLANNING 期限 210 秒矛盾 | 续租判据改为命令期限 `deadlineAt`；中断结果区分“用户取消”与“期限届满”（见 3.3） |
| 一条陈旧租约中断整轮心跳 | `cancellationRequested` 用 `queryForObject` 查询可能不存在的行，空结果抛异常并从心跳循环中冒出 | 改用 `query` 判空；无匹配行时返回 `false`，交由后续更新命中 0 行判定为租约丢失并摘除（见 3.4） |
| 派发故障时 worker 许可永久泄漏 | `dispatchLane` 只在正常分支手写 `release()`，`commands.find` / `enqueueFront` / `submit` 抛异常时不归还，每失败一次永久少一个并发额度，累积后整条 lane 停摆 | 用 `handedOver` + `finally` 闭合许可归属；成功移交 execute、失败原地归还且只归还一次；新增 `buyforu_dispatch_failure_total` 计数器（见 3.6） |
| SSE 健康连接被判为代理缓冲 | 服务端心跳间隔与前端读超时都是 15 秒，每次心跳都与超时赛跑，前端永久降级为轮询并额外消耗读取令牌 | 服务端心跳 10 秒、前端读超时 25 秒，两个常量各自写明偏序约束；顺带修掉定时器泄漏与 unhandled rejection（见 3.7） |
| 终态事件不校验迁移是否命中 | 条件更新是 `void` 且丢弃影响行数，被 `recoverExpired` 抢走的旧 Worker 更新 0 行仍 append `command.completed`，SSE 上出现互相矛盾的终态 | 条件更新返回影响行数，仅 `==1` 才写终态事件；栅栏拒绝改用带 `execution_epoch` 的 `markFencedOut`，不再把赢家正在跑的 RUNNING 判死（见 3.8） |
| 恢复路径先重排队、后放用户许可 | `recoverExpired` 先把命令翻成可派发，100 毫秒后派发周期就捞到它，而 Redis 用户许可还被上一任执行占着（TTL 240 秒），同一个用户被自己的旧执行堵住最长 4 分钟 | 改为先按"即将被恢复"的判据取候选并释放许可，再翻状态；顺带删掉靠 15 秒时间窗反查的启发式（见 3.9） |
| 测试替身与生产语义分叉 | 内存 `EffectLedger` 把 `CommerceException` 缓存并重放失败，生产 `JdbcCommerceEngine` 靠事务回滚后真正重跑；且该实现位于主源集但只被测试使用 | 失败不再落账，重试即重跑；类移入 test 源集（见 3.9） |
| Outbox 回收被排空循环饿死 | 投递是"一次跑完整个积压才返回"的循环，与 CLAIMED 回收共用单线程调度器，Webhook 慢时回收任务长期排不上；排空本身也没有上限 | 回收改用独立 `outboxReclaimScheduler`；排空增加 `buyforu.outbox.max-per-cycle`（默认 100）与 `buyforu_outbox_cycle_capped_total`（见 3.9） |
| 在途调用无观测、受理事件写失败返回 500 | `InFlightCallRegistry` 无 TTL 无兜底清理却不可观测；`CommandService.accept` 在命令已落库入队后仍因写事件失败把 202 变成 500 | 注册 `buyforu_inflight_calls` 量规；受理/取消事件改为尽力而为并计 `buyforu_run_event_append_failed_total`（见 3.9） |

### 3.2 本次重点修复的两个取消 Bug

#### Bug 1：CANCEL 命令把自己的租约当成下单租约

原链路是：CONTROL Worker 先 claim CANCEL，再调用 `ShoppingWorkflowService.cancel`；如果只按 `runId` 查询活租约，CANCEL 自己就会被识别为冲突，导致取消永远返回“下单仍在处理”。

当前修复：

1. `ExecutionContext` 保存当前 commandId。
2. `RunLeaseRepository.hasConflictingLiveLease` 查询活租约时排除当前 commandId。
3. 没有执行上下文的直接调用仍会把任意活租约视为冲突，保留安全默认值。

对应测试：`MultiWorkerLeaseIT.currentControlCommandLeaseIsNotAConflictForItself`。

#### Bug 2：订单已经创建但 Agent 仍为 CREATING_ORDER 时被标记取消

仅判断租约是否结束是不安全的：租约结束只表示 Worker 不再续租，不能证明 Commerce 没有提交订单。旧实现可能出现 Commerce 有订单、Agent 却显示 CANCELLED 的事实分裂。

当前修复：

1. Commerce 增加 `findOrderBySnapshot(userId, snapshotId)` 只读端口和 MCP Tool。
2. `CREATING_ORDER` 取消前先检查当前 run 是否存在其他活租约。
3. 查询到订单时恢复 `ShoppingAgentState` 为 `COMPLETED`，保留原 CREATE_ORDER effect 标记为已应用。
4. 查询不到订单时使用已有幂等释放操作释放预占。
5. 释放后再次查询订单，关闭旧下单请求与取消操作之间的竞态：创建先获得预占锁则二次查询能看到订单；释放先获得锁则后续下单因预占不再 ACTIVE 而失败。
6. 只有 Commerce 明确没有订单时，Agent 才标记 `CANCELLED`。

对应测试：

- `ShoppingWorkflowServiceTest.cancelRecoversOrderThatBecomesVisibleWhileReservationIsReleased`
- `ShoppingWorkflowServiceTest.cancelSettledCreatingOrderReleasesReservation`
- `BudgetSnapshotIT.createdOrderCanBeResolvedByAuthoritativeSnapshot`

这条路径不会重放 `createOrder`，因此取消本身不会补建新订单。

### 3.3 Bug 3：heartbeat 用已运行时长判定卡住，确定性误杀长时间规划命令

#### 失败场景

1. 用户提交 PLANNING 命令，`deadlineAt = now + 210s`（`CommandService` 按 lane 定义）。
2. DeepSeek 响应慢或触发三级 Replan，命令持续运行。
3. `t = 90s`：`heartbeat()` 用 `now - 90s` 作为陈旧阈值，因 `startedAt` 早于该阈值判定“卡住”。
4. 停止续租并 `worker.interrupt()`；worker 阻塞在 `DependencyExecutor.future.get()`，抛 `InterruptedException`，被包装为 `DependencyInterruptedException`。
5. 该类型不在 `execute()` 的可重试分支内（只列了 `DependencyTimeoutException`、`CallNotPermittedException`、`BulkheadFullException`），落入 `catch (RuntimeException)`。
6. 命令被 `markFailed('COMMAND_EXECUTION_FAILED')`，`attempts` 不递增、不重试。

**性质是确定性失败而非偶发故障**：任务需求超过 90 秒时，重试多少次都在同一位置被杀。

#### 根因：全系统唯一的语义偏离

| 位置 | 陈活性判据 |
| --- | --- |
| `RunLeaseRepository.recoverExpired()` | `lease_until <= now()` |
| `RunLeaseRepository.hasConflictingLiveLease()` | `lease_until > now()` |
| `RunLeaseRepository.isCurrent()` | `lease_until > now()` |
| `CommandWorker.heartbeat()`（修复前） | **`startedAt < now - 90s`** |

前三处统一以 `lease_until` 为准，只有 heartbeat 用第二套时间判据。

#### 当前修复

1. `shouldRenewLease` 签名与语义重定义为 `(Instant deadlineAt, Instant now)`：只有命令仍在期限内才续租，已运行时长不构成终止理由。
2. `heartbeat()` 删除 `minusSeconds(90)` 魔法数字，判据改为 `deadlineAt`。该参数无需新增配置——期限已由 `CommandService` 按 lane 定义为 PLANNING 210s / TRANSACTION 50s / CONTROL 15s。
3. `execute()` 在 `catch (RuntimeException)` **之前**新增 `DependencyInterruptedException` 分支，按 `cancellationRequested` 区分终止原因：用户取消 → `markCancelled('RUN_CANCEL_REQUESTED')`；期限届满 → `markFailed('COMMAND_DEADLINE_EXCEEDED')` 并累加 `buyforu_command_deadline_terminated_total`。
4. `classify` 新增 `DependencyInterrupted` 判定且**排在 `Timeout` 之前**，避免把“被终止”误报成“外部服务超时”。
5. `safeMessage` 补充 `COMMAND_DEADLINE_EXCEEDED` 公开文案。

#### 两个易错点（记录以备后续维护）

- `catch` 顺序：`DependencyInterruptedException` 是 `RuntimeException` 子类，分支必须排在通用分支之前，否则永远落不进来——这正是该缺陷能静默存在的原因。
- 不能用 `markExpired()` 终止 RUNNING 命令：其 SQL 限定 `status IN ('QUEUED','RETRY_WAIT')`，对 `RUNNING` 状态会静默漏更新（`jdbc.update` 影响 0 行且不报错）。

#### 不变量

- I1：未超过 `deadlineAt` 就必须续租，不论已运行多久。
- I2：停止续租的唯一理由是 `cancel_requested=true` 或 `deadlineAt <= now`。
- I3：全系统陈活性判定统一以 `lease_until` / `deadlineAt` 为准，不存在第二套判据。
- I4：期限与容量参数均可配置，代码中无裸魔法数字。
- I5：被中断的命令可归类为 `CANCELLED` / `EXPIRED` / `FAILED` 之一，且指标可区分。

#### 对应测试

- `CommandWorkerTest.LeaseRenewal.renewsEvenAfterRunningBeyondNinetySeconds`（核心回归锁：运行 180 秒但期限未到仍需续租）
- `CommandWorkerTest.LeaseRenewal.renewsWhenDeadlineMatchesFullPlanningWindow`（209 秒边界）
- `CommandWorkerTest.LeaseRenewal.stopsRenewingOnceDeadlinePassed` / `stopsRenewingExactlyAtDeadline`
- `CommandWorkerTest.FailureClassification.interruptedCallIsClassifiedAsDeadlineExceeded`
- `CommandWorkerTest.FailureClassification.timeoutIsStillClassifiedAsDependencyTimeout`（防止新规则抢走既有分类）

原有 `staleOrMissingStartTimeControlsLeaseRenewal` 断言把缺陷行为固化为预期，已随本次修复重写。

#### 已知取舍

假死线程（例如死循环）会占用租约至期限届满，PLANNING 最长 210 秒。这是有界上界，且崩溃实例仍由 `recoverExpired()` 兜底，因此可接受。若未来需要提前识别假死，可在业务图节点推进时记录进展时间（方案 C），但需扩大改动面，本次不做。

### 3.4 Bug 4：一条陈旧租约会中断整轮心跳，拖垮同实例上所有在跑的命令

本缺陷不是代码走查发现的，而是为 Bug 3 补集成测试时被测出来的——单测无法暴露，因为它只在"读不到租约行"时出现。

#### 失败场景

1. 命令 A 领取租约（epoch=5），开始执行。
2. A 执行时间超过租约时长（30 秒），期间任意一次续租窗口被错过——调度饥饿、GC 停顿、CPU 抢占都足以造成。
3. `recoverExpiredLeases()` 每 5 秒执行一次，回收 A 的租约并把 `agent_run_execution` 的 `active_command_id`、`lease_until` 清空。
4. A 的 `execute()` 尚未返回，`activeLeases` 里仍留着 epoch=5 的旧租约。
5. 下一次心跳调用 `cancellationRequested(lease)`，该 SQL 以 `run_id + active_command_id + execution_epoch` 三元组匹配，此时命中 0 行。
6. `queryForObject` 对空结果抛 `EmptyResultDataAccessException`。
7. 异常从 `activeLeases.forEach` 中冒出，**整轮心跳在此中断，排在后面的所有租约全部错过这一轮续租**。

危害高于 Bug 3：Bug 3 只杀掉超时的那一条命令，本缺陷是跨命令的连带损伤。陈旧条目在 `execute()` 的 `finally` 之前不会消失，因此**之后每一次心跳（每 10 秒）都在同一条目上中断**，该实例上所有在跑命令的租约会持续得不到续租，直至集体被 `recoverExpired()` 回收并降级为 RETRY_WAIT。

触发条件比"跨实例竞争"常见得多：单实例、单个慢命令即可复现。

#### 根因

`RunLeaseRepository.cancellationRequested` 用 `jdbc.queryForObject` 查询一个**可能不存在**的行。同类中其他 `queryForObject` 都是 `count(*)` 或带 `COALESCE` 的标量子查询，恒返回一行，因此只有这一处有隐患——而它恰好位于心跳循环内部。

#### 当前修复

`cancellationRequested` 改用 `jdbc.query`，无匹配行时返回 `false` 而不是抛异常：

- 语义正确：查不到行意味着本实例的租约已失效，而非"用户没取消"。
- 自愈：返回 `false` 后判定继续走到 `leases.heartbeat(...)`，该更新以相同三元组为条件，命中 0 行 → `LEASE_LOST` → 从 `activeLeases` 摘除该条目。陈旧租约在**同一轮**内被清除，循环得以继续。

同时把心跳的逐条逻辑从私有 lambda 抽成包级私有方法 `renewLease(...)` 与 `heartbeatOver(...)`，使集成测试能在真实 PostgreSQL 上驱动整轮判定，并验证"单条异常不中断整轮"。

#### 一个易错点（记录以备后续维护）

`heartbeatOver` 不能在 `forEach` 期间对传入的 Map 做结构修改：`ConcurrentHashMap` 允许，`LinkedHashMap` 会抛 `ConcurrentModificationException`。生产代码用的是 `ConcurrentHashMap`，因此不会触发，但测试缝传入的可能是任意 Map。实现上改为先收集待摘除的 key、遍历结束后统一摘除，对任何 `Map` 都成立。

#### 不变量

- I6：心跳循环中任何单条租约的处理失败，都不得影响其余租约在该轮的处理。
- I7：读取"可能不存在的行"禁止使用 `queryForObject`；只能用 `query` 后判空，或 `count(*)` / `COALESCE` 这类恒返回一行的写法。

#### 对应测试

`CommandWorkerHeartbeatIT`（新增集成测试，7 条）：

- `renewsPlanningCommandThatRanBeyondNinetySeconds` — 在真实库上锁死 Bug 3 的回归
- `stopsOnceThePersistedDeadlineHasPassed` — 直接改库里的 `deadline_at`，证明判定读的是持久化列而非内存对象
- `stopsExactlyAtTheDeadline` / `stopsWhenCancellationWasRequested`
- `toleratesALeaseThatRecoveryAlreadyReclaimed` — 本缺陷的直接回归锁
- `staleLeaseDoesNotAbortTheRestOfTheHeartbeatCycle` — 核心：陈旧租约不得拖垮整轮
- `reportsLeaseLostWhenAnotherWorkerTookOver` — 被更高 epoch 接管时报告丢失而非假装成功

#### 已知取舍

`heartbeatOver` 与 `renewLease` 是包级私有而非私有，属于为可测性开的一道缝。代价是并发包内部多出两个非 API 方法；收益是"判定依据与持久化期限是否一致"这类缺陷能被集成测试拦住——Bug 3 和 Bug 4 都属于这一类，纯单测两次都没能发现。

### 3.5 排查过但不构成缺陷的一项：`JdbcConversationMemory` 的 `queryForObject`

`JdbcConversationMemory.appendUserMessage`（第 29 行）同样用 `queryForObject` 读取可能不存在的行，表面看违反不变量 I7。核查结论是**不可达，故不修改**：

- 全仓不存在任何删除 `agent_schema.conversation` 的 SQL 或 Java 路径。
- 第 25-28 行的 `INSERT ... ON CONFLICT (conversation_id) DO NOTHING` 与随后的 `SELECT ... FOR UPDATE` 处于同一事务内，行必然存在。

`cancellationRequested` 与之的本质区别在于前者有明确可达路径（租约被 `recoverExpired()` 回收），此处没有。为不可达状态增加防御分支只会引入永远走不到、也无法有效测试的代码，且若该状态真的出现，静默放行比抛异常更容易掩盖问题。

判断标准记录下来：不变量 I7 的适用前提是"行可能不存在"，不能以写法相似为由一律改写。

### 3.6 Bug 5：派发路径在故障时不归还 worker 许可

#### 失败场景

`CommandWorker.dispatchLane`（第 169 行）先用 `permits.tryAcquire()` 占一个并发额度，之后依次调用 `commands.find`、`commands.markExpired`、`fairQueue.enqueueFront`、`executor.submit`。修复前只有几条正常分支显式 `release()`，这些调用**抛异常时许可不会归还**，而 `dispatch()` 也没有外层捕获——`@Scheduled` 只把异常写进日志后继续下一个周期。

`application.yml` 刻意把 `connection-timeout` 设为 1000ms（快速失败、不堆积线程），这意味着高负载下拿到连接失败**是预期事件而不是异常事件**。每一次都永久吃掉一个额度：PLANNING 20 / TRANSACTION 16 / CONTROL 4。反复发生会把某条 lane 的并发度蚕食到 0，此后该 lane 不再派发任何命令，且**只能通过重启进程恢复**——因为它不是流量问题，而是计数器已经错了。

#### 根因

许可的"取得"和"归还"跨越了两个方法（`dispatchLane` 取得、`execute` 的 finally 归还），中间却是一串可以抛异常的调用。修复前用"在每个正常出口手写 release"来维护这个不变量，等于把账目正确性交给人工枚举分支——漏掉任意一条就静默丢额度。

#### 当前修复

- `dispatchLane` 用 `handedOver` 标志 + `finally` 保证"要么本方法归还、要么移交 execute"，不再依赖逐分支枚举。
- 抽出 `handOver(command, permits, executor, holdsUserPermit)`：提交成功返回 `true`（许可交给 execute），提交失败返回 `false` 且**不自行归还**——归还统一由调用方的 `finally` 执行，避免两边都归还把额度放大。
- CONTROL 分支（第 69-74 行）同样改成"提交失败原地归还"，此前 `control.submit` 抛出 `RejectedExecutionException` 也会漏额度。
- 新增计数器 `buyforu_dispatch_failure_total{queue_class=...}`，让这类故障可告警，而不是只留一行日志。
- Redis 协调层抖动仍走静默返回（不计入该计数器）：按每个派发周期（100ms）计一次失败会让告警失去意义。

#### 不变量

I8：`permits.tryAcquire()` 成功之后，该许可必须被归还**恰好一次**——由 `dispatchLane` 或由 `execute`，二者必居其一。

#### 对应测试

`CommandWorkerPermitTest` 把真实的 `Semaphore` 传进 `dispatchLane` 再数一遍，覆盖四条故障出口加一条成功移交：

| 用例 | 断言 |
| --- | --- |
| `returnsPermitWhenCommandLookupFails` | 查询命令抛 `DataAccessResourceFailureException` → 许可数回到初值，失败计数器 +1 |
| `returnsPermitWhenQueuePollFailsSilently` | Redis 抖动 → 许可数回到初值，且**不**计入失败计数器 |
| `returnsPermitWhenUserAlreadyHasARunningCommand` | 用户已有在跑命令 → 许可归还且命令被塞回队头 |
| `returnsPermitWhenExecutorRejectsSubmission` | 线程池拒绝 → 许可归还（并验证确实走到了提交这一步） |
| `handsPermitOverExactlyOnceOnSuccess` | 成功移交 → `execute` 归还一次，`dispatchLane` 不得再还一次 |

回归锁经过反向验证：临时撤销 `finally` 归还后，前四条以 `expected: <2> but was: <1>` 失败，正是"永久少一个额度"的现象。

`dispatchLane` 由 private 放宽为包级私有，属于为可测性开的第二道缝（同 3.4 的 `heartbeatOver`）：许可账目只有把真实 `Semaphore` 交进去数一遍才能证明，静态断言看不出"少还一个"或"多还一个"。

#### 已知取舍

引入 `finally` 后，`dispatchLane` 内部的每条 `return` 都隐含一次 `release()`，可读性略低于原来的显式释放。这是刻意的：把不变量交给语言结构而不是人工分支，正是这类缺陷的修复要点。

### 3.7 Bug 6：SSE 心跳间隔与前端读超时相等，健康连接被判为代理缓冲

#### 失败场景

服务端 `RunEventController` 每 15 秒发一次心跳帧，前端 `followRun` 给 `reader.read()` 设的软超时**也是 15 秒**，一旦超时即 `break` 并永久降级为 500ms→2s 轮询。

两个常量相等意味着每次心跳都在和超时赛跑：健康连接也会以接近一半的概率被判成"开发代理缓冲了 SSE"。后果是生产环境 SSE 实际不可用，退回轮询；同时每次重连都消耗读取令牌桶（`readUserBurst=30`、`readUserPerMinute=120`），长时间任务会额外触发 429。

#### 根因

"用读超时探测代理缓冲"这个思路本身没问题，缺的是**两个常量的偏序约束**：客户端超时必须显著大于服务端心跳间隔。此前两个数字分别写在前后端两个文件里，没有任何一处记录它们必须满足的关系。

#### 当前修复

- 服务端提取常量 `HEARTBEAT_INTERVAL_MS = 10_000`，替换原先散落的 `15_000` 魔数，并在注释里写明当前约定：**服务端 10 秒心跳、前端 25 秒读超时**。
- 前端提取常量 `SSE_READ_TIMEOUT_MS = 25_000`，注释说明它必须显著大于服务端心跳间隔，且指出"两个常量相等时每次心跳都在和超时赛跑"。
- 顺带修掉两个前端小缺陷：超时用的定时器在每次循环结束时 `clearTimeout`（一条连接可能收到成百上千条事件，否则定时器会一路积到 25 秒后才触发）；超时后被 `reader.cancel()` 拒绝的那个 read promise 先挂 `catch(() => undefined)`，避免控制台出现 unhandled rejection。

#### 不变量

I9：前端 SSE 读超时 > 服务端心跳间隔。违反时系统不会报错，只会静默降级到轮询，因此这条偏序关系必须写在两个常量的注释里。

#### 对应测试

前端没有自动化测试框架，验证方式是隔离环境下的严格类型检查（`tsc --noEmit --strict --noUnusedLocals`，见 5.1）。偏序关系本身是常量取值约定，靠注释与本节记录维护；若后续引入前端测试，应补一条断言 `SSE_READ_TIMEOUT_MS > 服务端心跳` 的用例。

### 3.8 Bug 7：终态事件不校验迁移是否命中，且栅栏拒绝会杀掉已被接管的命令

#### 失败场景

前一任租约过期、后一任已接管时，两个执行实例都会对同一条命令做终态迁移：

1. 实例 A 持有 run R 的租约（epoch=5）执行命令 C。A 停摆过久，租约到期，`recoverExpired` 把 C 翻成 RETRY_WAIT 并清空 `agent_run_execution`。
2. 实例 B 重新领取 C（epoch=6），开始执行。
3. A 恢复运行，写回被 epoch 栅栏拒绝，抛 `StaleExecution`。A 手上的 `markFailed(C,"STALE_EXECUTION")` 只带 `WHERE status='RUNNING'`，命中的是 B 正在执行的那一行——C 被判定为 FAILED，而 B 还在跑。
4. B 正常执行完，`markSucceeded` 命中 0 行（状态已是 FAILED），却照样 append `command.completed`。

结果：一条已经产生副作用的命令以 `STALE_EXECUTION` 收尾，SSE 上还会先出现 `command.failed`、后出现 `command.completed`；断线续传的客户端按后收到的错误终态收尾。同类问题也出现在 `retryLater`（命令已被恢复却发 `command.retry-wait`）和取消路径上。

#### 根因

- `markSucceeded` / `markFailed` / `markCancelled` / `retryLater` / `markExpired` / `markAdmissionRejected` 都是 `void`。SQL 明明带状态前置条件，影响行数却被丢弃，调用方无从判断"这次迁移是不是我赢的"。
- 栅栏拒绝的语义被写成了"命令失去写权限 ⇒ 命令应该失败"。真实语义是"**本实例**失去了写权限"：命令可能已被更高 epoch 的实例接管并正常运行，此时用不带 epoch 的 `markFailed` 就是把赢家判死。

#### 当前修复

- 上述条件更新全部返回影响行数（`markAdmissionRejected` / `markExpired` 的调用方目前不需要该值，但签名统一，避免下一个调用方重复踩坑）。
- `CommandWorker` 新增 `appendTerminalEvent(updatedRows, command, eventType, payload)`：只有 `==1` 才 `events.append`，命中 0 行时计入 `buyforu_terminal_event_suppressed_total{event_type}`。计数是必需的——静默丢弃会让这类竞态在线上完全不可观测。
- 栅栏分支改用 `CommandRepository.markFencedOut(commandId, staleEpoch, ...)`，SQL 增加 `AND (?::bigint IS NULL OR execution_epoch=?)`。拿不到租约（`lease == null`）时传 `null` 退化为不校验 epoch；这条路径进不了 `ExecutionContext`，本就不存在被接管的可能。

#### 不变量

- 一条命令的终态事件至多写出一次，且必须由真正完成该状态迁移的那次执行写出。
- 栅栏拒绝只能终止"仍属于自己 epoch"的 RUNNING 命令。

#### 对应测试

`CommandWorkerTerminalEventTest`（6 例）走完整"派发 → 领取租约 → 执行 → 终态迁移"路径，真实 `Semaphore` + 真实单线程执行器：

| 用例 | 断言 |
| --- | --- |
| `suppressesCompletedEventWhenAnotherPathAlreadyOwnsTheTerminalState` | `markSucceeded` 返回 0 → 不写 `command.completed`，抑制计数为 1 |
| `suppressesFailedEventWhenAnotherPathAlreadyOwnsTheTerminalState` | `markFailed` 返回 0 → 不写 `command.failed` |
| `suppressesRetryWaitEventWhenTheCommandIsNoLongerRunning` | `retryLater` 返回 0 → 既不写 `command.retry-wait` 也不写 `command.failed` |
| `reportsTerminalEventOnlyWhenTheTransitionWins` | 返回 1 → 正常写 `command.completed`，抑制计数为 0 |
| `doesNotFenceACommandANewerEpochAlreadyTookOver` | 传递自身 epoch（7）且 `markFencedOut` 返回 0 → 不写 `command.failed` |
| `fencesItsOwnStillRunningCommand` | `markFencedOut` 返回 1 → 写 `command.failed` |

反向验证：把 `appendTerminalEvent` 的判断临时改成恒真，上述 6 例中 4 例失败（`Wanted but not invoked`），证明测试确实锁住了行为而不是恒绿。

#### 已知取舍

- 栅栏拒绝仍然会消耗 `buyforu_fenced_write_rejected_total` 计数并写 WARN 日志，即使这次拒绝没有终止任何命令。这是刻意的：被栅栏拒绝本身就是"旧实例还在写"的信号，值得看见。
- `markExpired` 的返回值目前在派发与索引重建路径上未被使用（那两条路径不发事件）。

### 3.9 本轮一并修复的四项

| 项 | 失败场景与根因 | 当前修复 | 对应测试 |
| --- | --- | --- | --- |
| 恢复路径先重排队、后放用户许可 | `recoverExpired` 把命令翻成 RETRY_WAIT 且 `available_at=now()`，100 毫秒后的派发周期就捞到它；此时 Redis 用户许可仍被上一任执行占着（TTL 240 秒），`tryAcquireUser` 失败 → 命令被塞回队首 → 下个周期再弹一次，同一用户被自己的旧执行堵住最长 4 分钟。旧实现还靠 `error_code + 15 秒时间窗` 反查"刚恢复的命令"，既可能漏也可能误伤别的实例恢复的命令 | `CommandRepository.recoverableCommands(limit)` 按"即将被恢复"的判据取候选（`status='RUNNING' AND NOT EXISTS(活跃租约)`，与 `recoverExpired` 第二条更新同源），`CommandWorker` 先逐条 `releaseUser` 再 `recoverExpired`；redis 失败按条吞掉，不阻塞状态恢复 | `CommandWorkerRecoveryTest`（4 例）：`InOrder` 断言释放先于翻状态、无候选时不释放、release 抛异常仍完成恢复、DB 故障不冒泡进调度线程 |
| 测试替身与生产语义分叉 | 内存 `EffectLedger` 把 `CommerceException` 缓存并在重放时抛出，而生产把业务写入与 `effect_record` 放在同一事务：失败回滚后记录消失，重试是真正重跑。测试替身比生产更严格，一个在生产上会重跑成功的请求在测试里会一直拿到旧失败 | 失败不再落账；类从 `src/main` 移到 `src/test`（只有 `InMemoryCommerceEngine` 使用它） | `EffectLedgerTest`（4 例）：失败后重试真的重跑、成功结果只重放不重算、幂等键跨 effectId 去重、键复用冲突 |
| Outbox 回收被排空循环饿死 | 投递是"一次跑完整个积压才返回"的循环，与 CLAIMED 回收共用 `outboxScheduler`（单线程）。Webhook 慢但成功时一轮能跑很久，固定延迟的回收任务只能排在后面，崩溃实例留下的 CLAIMED 行迟迟回不到 PENDING。排空循环本身也没有上限，停机时无法及时收尾 | 回收改用独立 `outboxReclaimScheduler`；排空上限 `buyforu.outbox.max-per-cycle`（默认 100），触顶计 `buyforu_outbox_cycle_capped_total`。触顶不是错误，但持续增长说明积压追不上来 | `OutboxDispatchIT.stopsDrainingOnceThePerCycleCapIsReached`（Testcontainers）：3 条事件、上限 2 → 前 2 条 PUBLISHED、第 3 条仍 PENDING、触顶计数 1，下一个周期补完 |
| 在途调用无观测、受理事件写失败返回 500 | `InFlightCallRegistry` 没有 TTL 也没有兜底清理，一旦某条路径漏掉 `clear`，条目永久驻留且完全不可观测。`CommandService.accept` 在命令已落库、已进（或在）全序控制之后仍会因为写 `command.accepted` 失败把 202 变成 500，客户端会以为命令没被受理，而它其实已经在跑 | 注册 `buyforu_inflight_calls` 量规（必须随命令结束回落到 0）；受理与取消事件改为尽力而为，失败计 `buyforu_run_event_append_failed_total{event_type}` 并写 WARN，受理结果不变 | `InFlightCallRegistryTest.publishesInFlightSizeAsGauge`、`CommandServiceAcceptTest`（3 例）：事件写入失败仍返回受理结果、取消标记不受影响、正常路径仍写事件 |

### 3.10 仍需单独确认的问题

以下两项已定位到具体位置并确认存在，但都需要与调用方/对端约定协议，不在本轮范围内：

| 级别 | 问题 | 位置 |
| --- | --- | --- |
| P2 | Webhook 签名不含时间戳，合法请求可被无限重放（需改协议，须与对端约定） | `WebhookDomainEventPublisher` 第 43-51 行 |
| P2 | Commerce MCP 使用全局单令牌，且 `userId` 由调用方传入、Commerce 侧不校验身份，令牌泄露即等于全用户数据读写权 | `McpSecurityConfiguration` 第 29-31 行、`CommerceMcpTools` 第 24-27/96-102 行 |

`CommandService.accept` 全程非事务这一点本轮只做了降级（事件写入失败不再影响受理结果），**没有**引入补偿扫描：`command.accepted` 缺失时 SSE 只是少一个锚点，Worker 的终态事件仍会补上；真正需要补偿的是"命令已落库但既没入队也没被标记拒绝"的窗口，该窗口由 `reconcileRedisIndex` 每 5 秒扫描 `queuedWithoutIndex` 覆盖。

本轮同时核实了一批**并非缺陷**的实现，避免后续重复排查：`CommandService.accept` 对非 START 命令先 `assertRunOwner` 堵住 IDOR；`JdbcConversationMemory` 的先锁行再验主人；`effect_record` 在 `effect_id`（主键）与 `idempotency_key`（唯一索引）上双唯一；`createOrder` 的审批归属/摘要/时效三重校验配合 `orders.source_snapshot_id` 唯一约束；`agent_run_event` 的 7 天保留确有定时任务调用；`SearchRequest` 在领域层夹紧 `limit∈[1,50]`、`quantity∈[1,99]`，MCP 层无从放大查询。

## 4. 当前事务与恢复语义

### 4.1 预占响应未知

`PREPARING_CONFIRMABLE_ORDER` 且 `activeEffect=PENDING_EFFECT` 时，取消只复用原 effectId 重放 prepare；缺货、SKU 消失或预算不满足表示没有可释放快照，不允许进入 Candidate Fallback、Search Replan 或 DeepSeek。成功拿到快照后再幂等释放。

### 4.2 下单响应未知

- APPROVE 恢复：使用原 create effectId 恢复 Commerce 结果，成功则保存 COMPLETED。
- CANCEL 恢复：只读查询订单；不会通过取消动作重放 createOrder。
- 查询基础设施不可用：命令失败或重试等待，不得假设“没有订单”。
- 已经确认存在订单：不能静默回滚，Agent 必须展示 COMPLETED。

### 4.3 快照过期和图 checkpoint 不一致

过期审批会先释放旧预占、递增 `planVersion`、保存新的 PREPARING 状态。若在重新预占前崩溃，恢复逻辑将 `approvalRoute` 设为 `requote`，不会使用人工等待节点的默认 `rejected` 路由。恢复后旧 snapshot 的 APPROVE 不会直接创建订单，只返回新快照或新的等待状态。

## 5. 验证矩阵

### 5.1 本地验证命令

```bash
./mvnw clean test
./mvnw -Pintegration verify       # Docker daemon 可用时
cd web && npm run typecheck && npm run build
docker compose config
git diff --check
```

`web/node_modules` 不可用时（例如受限执行环境不允许在项目目录内安装依赖），前端改动的最小等价验证是：把 `src/api.ts` 与 `src/types.ts` 复制到临时目录、为 `src/auth.ts` 提供 `accessToken` 桩，再用 `tsc --noEmit --strict --noUnusedLocals` 检查。这样能覆盖语法与类型正确性，但不覆盖 Vite 生产构建和 React 组件。

### 5.2 当前验证范围

| 层级 | 覆盖内容 |
| --- | --- |
| 单元测试 | 固定图、PlanSpec、预算、Commerce effect、取消、订单恢复、MCP cause 分类、事务内网络调用保护、RAG 切块、派发路径的 worker 许可账目（四条故障出口 + 一条成功移交，见 3.6）、终态事件抑制与栅栏 epoch 校验（六例，见 3.8）、恢复路径的释放/翻状态顺序（四例）与 effect ledger 失败语义（四例）、受理事件降级（三例）与在途调用量规（见 3.9） |
| Commerce 集成测试 | PostgreSQL 预算快照、订单按快照解析、Outbox 投递与单周期上限 |
| Agent 集成测试 | Run ownership、多 Worker claim、过期租约、当前 CONTROL 租约排除、心跳续租判定（含陈旧租约不拖垮整轮）、12 run × 8 worker 并发争用下的租约互斥性与 epoch 栅栏、200 命令 × 20 用户全链路吞吐（受理 → 限流 → 公平队列 → 领取 → 执行 → 释放，实测吞吐 878 条/秒、端到端 p95=278ms，并断言全部成功、无残留租约、队列排空、无用户饿死） |
| 前端构建 | TypeScript 编译、Vite 生产构建 |
| 外部联调 | DeepSeek/MCP/Ollama 需要本地凭据和运行服务，不能由离线单测代替 |

## 6. 有意不实现的需求

这些不是本阶段 Bug，而是明确的范围边界：

- 支付、退款、发货、物流、售后和逆向交易。
- 多商家拆单、购物车、复杂促销叠加和账户余额。
- 独立 API Gateway、Kafka、分库分表、跨地域多活。
- LLM 自动选择写 Tool、动态生成任意 DAG、多模型隐藏降级。
- 生产级完整支付幂等、对账和风控平台。

这些能力未来可以在当前端口和 Outbox 边界上扩展，但不应为了展示效果而伪造为已实现。

## 7. 文档和代码的维护规则

- 新增交易状态、命令状态或 MCP Tool 时，必须同时更新 [PROJECT_DESIGN.md](PROJECT_DESIGN.md) 的模型/API/Tool 表。
- 发现一致性问题时，先写失败场景和不变量，再修改代码和测试；不能只通过 `grep` 或字符串替换宣称完成。
- 已执行的 Flyway 历史迁移不能修改；需要新增版本。
- 所有文档中的“已实现”必须有对应生产代码和至少一个自动化验证；外部依赖联调要单独注明。
