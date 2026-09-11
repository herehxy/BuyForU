# BuyForU 代码结构导览

这份文档面向第一次阅读项目的人。建议先理解模块边界和一次请求如何流动，再阅读具体实现。

## 1. Maven 模块

| 模块 | 端口 | 职责 | 不应该负责 |
| --- | --- | --- | --- |
| `commerce-port` | 无 | 定义交易能力接口及跨模块领域对象 | 数据库、MCP、Agent 编排 |
| `commerce-service` | 8081 | 商品、价格、优惠、库存、履约、订单的唯一事实源 | 理解自然语言、决定用户需求 |
| `agent-app` | 8080 | 身份认证、LLM 规划、固定图编排、记忆、RAG、人工确认 | 自行计算金额或修改库存 |
| `web` | 5173 | 登录和人机交互界面 | 保存密钥、计算交易结果 |
| `infra/keycloak` | 8082 | 本地 OIDC Realm 配置 | 业务用户数据和交易数据 |

## 2. Java 分层

### agent-app

```text
api             HTTP Controller、安全配置、错误协议
application     用例编排；固定图及图节点真正执行的业务动作
domain          PlanSpec、AgentState、Replan 等纯领域规则
infrastructure  PostgreSQL、MCP、pgvector 等技术实现
config          启动时必填配置校验
```

最重要的三个类：

1. `GraphShoppingWorkflow`：HTTP 用例入口，负责启动/恢复 LangGraph 和命令幂等。
2. `FixedShoppingGraph`：固定状态图拓扑。LLM 无权增加或删除节点。
3. `ShoppingWorkflowService`：每个图节点调用的真实业务动作，包括搜索、预占、审批和下单。

### commerce-service

```text
api             将 CommerceGateway 暴露为 MCP Tools，并校验服务凭证
application     交易事务和一致性实现
infrastructure  Outbox 投递、预占过期任务、生产 Webhook
```

核心类 `JdbcCommerceEngine` 是交易事实源：价格、优惠、库存和订单只能由它及其数据库规则决定。

## 3. 一次购物请求的调用链

```text
React 页面
  -> AgentRunController（从 JWT sub 获取 userId）
  -> GraphShoppingWorkflow（幂等 runId + 固定图启动/恢复）
  -> CommandWorker / RunLeaseRepository（短租约 + execution epoch 串行推进）
  -> FixedShoppingGraph（固定拓扑和 checkpoint）
  -> ShoppingWorkflowService（图节点动作）
  -> CommerceGateway（应用层端口）
  -> McpCommerceGatewayAdapter
  -> SpringMcpCommerceToolClient（MCP SDK + 调用审计）
  -> CommerceMcpTools
  -> JdbcCommerceEngine（事务、行锁、effect ledger）
  -> PostgreSQL
```

规划调用链：

```text
ShoppingWorkflowService.planNewRun
  -> ConversationMemory 读取最近对话
  -> KnowledgeRetriever 从 pgvector 取证据
  -> SpringAiPlanningModel 调用 DeepSeek
  -> PlanSpecValidator 拒绝不可执行或越权的计划
  -> 固定图按照 PlanSpec 数据执行
```

## 4. 固定状态图

| 阶段 | 含义 | 是否等待用户 |
| --- | --- | --- |
| `NEW` | 尚未规划 | 否 |
| `NEEDS_CLARIFICATION` | 缺少品类、地址等必要条件 | 是 |
| `SEARCHING` | 调用 Commerce 搜索和排序 | 否 |
| `PRESENTING_CANDIDATES` | 展示候选商品 | 是 |
| `PREPARING_CONFIRMABLE_ORDER` | 计算权威报价并预占库存 | 否 |
| `WAITING_APPROVAL` | 快照已冻结，等待最终确认 | 是 |
| `CREATING_ORDER` | 使用审批证明幂等创建订单 | 否 |
| `NEEDS_CONSTRAINT_RELAXATION` | 硬条件无结果，等待用户明确授权修改 | 是 |
| `COMPLETED` | 订单已创建 | 终态 |
| `CANCELLED` | 用户取消，预占已释放 | 终态 |

## 5. 三类“状态”不要混淆

- `ShoppingAgentState`：业务状态，页面和恢复逻辑以它为准。
- LangGraph checkpoint：图执行位置，决定下一节点从哪里继续。
- `EffectContext` / `effect_record`：外部副作用状态，解决“库存或订单已成功，但 Agent 随后崩溃”的窗口。

这三者分别解决业务展示、流程恢复和副作用幂等，不能互相替代。

## 6. 交易安全边界

- `budgetMax` 是应付合计上限（商品小计 − 优惠 + 运费），不是吊牌单价。搜索与快照使用同一套计价；快照在扣库存前若应付超预算则拒绝预占。
- Agent 只能传递条件、选择和审批证明，不能提供最终金额。
- `prepareConfirmableOrder` 在同一事务中重新报价、锁库存并生成摘要快照。
- 用户批准时必须提交当前 `snapshotId + summaryHash`。
- `createOrder` 再次验证用户、快照、审批时间和预占状态。
- 所有副作用先登记 `effectId/idempotencyKey`，成功结果可安全重放。
- 订单与 Outbox 事件在同一数据库事务提交。Outbox 投递先短事务认领，HTTP 在事务外发送；至少投递一次，接收方按 `eventId` 去重。
- 写命令的幂等键在同一个用户的同一个 run 内唯一，失败后换新 key 再试。
- 非 START 命令先验 run 主人再落库；SSE 只认 `agent_run` 或 START 命令的用户。
- 约束放宽必须由用户点名字段，不能靠句子里的“元”“预算”推断。

## 7. PostgreSQL Schema

- `commerce_schema`：商品、SKU、库存、预占、快照、订单、优惠、运费、effect ledger、outbox。
- `agent_schema`：会话、消息、Agent Run、业务 checkpoint、LangGraph checkpoint、审批、MCP 调用审计、知识文档及向量。

数据库变更只通过各模块 `src/main/resources/db/migration` 下的 Flyway 脚本演进。

## 8. 推荐阅读顺序

1. `commerce-port/CommerceGateway`
2. `domain/PlanSpec` 和 `domain/ShoppingAgentState`
3. `application/FixedShoppingGraph`
4. `application/GraphShoppingWorkflow`
5. `application/ShoppingWorkflowService`
6. `commerce-service/JdbcCommerceEngine`
7. MCP adapter、持久化及 RAG 实现
8. `web/src/App.tsx`

代码注释重点解释“为什么需要这层”和“一致性边界”，不会逐行翻译显而易见的 Java 语句。
