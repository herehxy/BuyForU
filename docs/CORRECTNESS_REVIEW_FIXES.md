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
| Agent 集成测试 | Run ownership、多 Worker claim、过期租约、当前 CONTROL 租约排除 |
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
