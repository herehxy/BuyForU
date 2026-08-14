# BuyForU 全链路 Review 与修复记录

## 1. Review 范围

本轮检查覆盖 `React → Agent HTTP → PostgreSQL 持久化命令 → Redis 公平队列 → LangGraph4j 固定图 → DeepSeek/RAG → Commerce MCP → 报价/库存预占 → 人工审批 → 订单`，同时检查多实例租约、幂等、SSE、输入边界、部署文件和自动化测试。

目标不是把项目扩成复杂平台，而是保证主体架构清晰、可运行，并且常见失败不会造成重复订单、库存泄漏、长事务连接占用或任务永久卡死。

## 2. 已修复的关键问题

| 问题 | 风险 | 修复 |
| --- | --- | --- |
| 审批时快照已过期却复用原 `effectId` | Commerce effect ledger 永远重放旧快照 | 释放旧预占并递增 `planVersion`，生成新的 prepare effect；增加可控时钟回归测试 |
| `budgetMin` 只参与搜索 | 最终报价可能违反用户下限 | `PrepareOrderRequest`、MCP DTO、请求哈希和 Commerce 最终报价同时校验上下限 |
| 预算上下限矛盾 | 搜索结果为空但原因不清楚 | `PlanSpecValidator` 明确拒绝 `budgetMin > budgetMax` |
| 过期租约被新 claim 覆盖 | 旧命令永久停在 `RUNNING` | claim 前恢复旧 active command；恢复器额外扫描无有效租约的孤儿 RUNNING 命令 |
| 旧 Worker 被栅栏拒绝后状态不收敛 | 命令看似仍执行 | 命令落为 `FAILED/STALE_EXECUTION`，同时记录事件与指标 |
| 取消排队中的 START | 控制 Worker 读取不到尚未创建的 run | 先取消同 run 的 QUEUED/RETRY_WAIT 命令；未创建 run 时直接完成取消命令 |
| 取消标记在重新 claim 时被清空 | 被取消任务仍可能继续执行 | 非 CONTROL 命令看到 `cancel_requested` 直接取消，不领取租约 |
| 预占响应未知时取消 | Commerce 可能已扣库存但 Agent 没快照 | 使用相同 effectId 恢复 prepare 结果，再执行幂等释放 |
| 下单响应未知时取消 | 可能已有订单却向用户报告取消成功 | `CREATING_ORDER` 阶段拒绝取消，先恢复同 effectId 的订单结果 |
| 业务状态领先图 checkpoint | 重放节点因 phase 不匹配失败 | prepare/select 节点识别已推进的稳定业务阶段，并让图补齐 checkpoint |
| MCP `isError` 在熔断器外判断 | Tool 服务错误没有进入正确的熔断统计 | 在受保护 Callable 内区分领域拒绝、Schema 不兼容和基础设施故障 |
| 命令状态暴露原始下游错误 | 可能泄露 URL、请求内容或内部结构 | 对外只保存稳定错误码和中文公开信息，完整异常只进服务日志 |
| Redis 入队异常返回 500 | 客户端无法判断协调层不可用 | 非容量异常统一映射为 `503 COORDINATION_UNAVAILABLE` |
| HTTP DTO 直接接收无界领域集合 | 超大列表/Map 和字段越权 | 增加独立 ConstraintInput、长度/数量边界、放宽字段白名单和统一 400 |
| 无条件信任代理头或只看到代理 IP | IP 限流失真 | 只有 `TRUSTED_PROXY_ADDRESSES` 中的来源可提供 X-Forwarded-For |
| 前端只轮询且刷新丢失活跃命令 | 额外查询压力、用户无法继续观察 | Bearer `fetch + ReadableStream` SSE，15 秒心跳检查，断线指数轮询兜底；sessionStorage 恢复活跃 command |
| 网络错误后立即更换幂等键 | 服务端已接纳时会生成第二条命令 | 网络/5xx 保留原 key，明确 4xx 才允许生成新 key；START 的 conversationId 在拿到 202 前保持稳定 |
| SSE notifier 为所有 run 永久建 signal | 长期运行后本地 Map 增长 | 连接 retain/release 引用计数；无观察者时不创建、不保留 signal |
| 商品搜索对每个 SKU 查询促销和运费 | 候选增多后出现 N+1 | 每次搜索一次加载有效规则，在内存计算候选应付；最终 prepare 仍重新权威报价 |
| Agent 集成测试扫描到 Commerce 的同版本迁移 | Flyway 报重复 V1，CI 实际失败 | Agent Testcontainers 固定使用本模块 migration 路径 |
| 最近任务/消息缺组合索引 | 数据增大后列表和上下文变慢 | V8 迁移补充实际查询顺序对应的索引 |
| Web 镜像刷新回调 404、SSE 被缓冲 | 容器化页面不可用或无实时进度 | Nginx SPA fallback、同源 API 代理、关闭 SSE buffering，并加入安全的 Docker build args |

## 3. 关键安全边界

- DeepSeek 只生成 `PlanSpec`，不能生成任意图或直接执行写 Tool。
- LangGraph4j 拓扑固定，人工审批节点不能绕过。
- 金额、促销、库存、履约和订单只由 Commerce Domain 计算。
- MCP 是 `CommerceGateway` 的传输 Adapter，应用层不依赖 MCP SDK。
- PostgreSQL 是命令、run、事件和交易事实来源；Redis 数据可重建。
- 网络调用前必须结束数据库事务；`NetworkCallGuard` 会在事务内调用时立即失败。
- MCP 写操作超时表示“结果未知”，恢复时必须复用相同 `effectId/idempotencyKey/requestHash`。

## 4. 验证方式

```bash
./mvnw -B test
./mvnw -B -Pintegration verify       # 要求 Docker daemon 可用
cd web && npm run typecheck && npm run build
docker compose config
git diff --check
```

重点回归包含：重复 effect 不重复扣库存、并发预占不超卖、快照过期后生成新快照、预算下限最终校验、旧 Worker 栅栏、同 run 单租约、MCP 可选字段 Schema、中文预算方向与品类纠偏。

## 5. 有意不增加的复杂度

以下内容不属于当前购物 Agent 主链，暂不加入，避免为了“看起来像大厂”而膨胀：独立 API Gateway、Kafka、分库分表、动态工作流 DSL、多模型隐藏降级、支付清结算、售后逆向链路、跨地域多活。

生产化时仍需要根据真实数据量和合规要求决定消息/checkpoint/Tool 审计的保存期限；不能在没有产品与审计策略时擅自删除用户记录。pgvector 当前允许不同 Embedding 维数，因此也没有盲目创建固定维数 HNSW 索引；数据量和模型固定后再增加对应表达式索引。
