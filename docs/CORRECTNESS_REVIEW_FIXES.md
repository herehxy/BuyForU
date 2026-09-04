# BuyForU 需求演进、Review 与修复记录

> 这份文档记录“为什么这样实现、Review 发现了什么、代码如何修复、如何验证”。
> 总体架构、API、数据模型和面试题见 [PROJECT_DESIGN.md](PROJECT_DESIGN.md)。
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

### 5.2 当前验证范围

| 层级 | 覆盖内容 |
| --- | --- |
| 单元测试 | 固定图、PlanSpec、预算、Commerce effect、取消、订单恢复、MCP cause 分类、事务内网络调用保护、RAG 切块 |
| Commerce 集成测试 | PostgreSQL 预算快照、订单按快照解析、Outbox 投递 |
| Agent 集成测试 | Run ownership、多 Worker claim、过期租约、当前 CONTROL 租约排除、心跳续租判定（含陈旧租约不拖垮整轮） |
| 前端构建 | TypeScript 编译、Vite 生产构建 |
| 外部联调 | DeepSeek/MCP/Ollama 需要本地凭据和运行服务，不能由离线单测代替 |

## 6. 有意不实现的需求

这些不是本阶段 Bug，而是明确的范围边界：

- 支付、退款、发货、物流、售后和逆向交易。
- 多商家拆单、购物车、复杂促销叠加和账户余额。
- 独立 API Gateway、Kafka、分库分表、跨地域多活。
- LLM 自动选择写 Tool、动态生成任意 DAG、多模型隐藏降级。
- 生产级完整支付幂等、对账和风控平台。

这些能力未来可以在当前端口和 Outbox 边界上扩展，但不应为了面试展示而伪造为已实现。

## 7. 文档和代码的维护规则

- 新增交易状态、命令状态或 MCP Tool 时，必须同时更新 [PROJECT_DESIGN.md](PROJECT_DESIGN.md) 的模型/API/Tool 表。
- 发现一致性问题时，先写失败场景和不变量，再修改代码和测试；不能只通过 `grep` 或字符串替换宣称完成。
- 已执行的 Flyway 历史迁移不能修改；需要新增版本。
- 所有文档中的“已实现”必须有对应生产代码和至少一个自动化验证；外部依赖联调要单独注明。
