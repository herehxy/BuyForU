# BuyForU 文件职责索引

本索引用于解释无法安全写入注释的文件，以及从文件名不容易看出职责的工程文件。Java、TypeScript、React、CSS、YAML 和 Maven 文件已经在源码内部包含职责及关键段落说明。

## 根目录

| 文件 | 职责 | 备注 |
| --- | --- | --- |
| `pom.xml` | Maven 聚合与版本管理 | 文件内部有 XML 注释 |
| `docker-compose.yml` | 本地 PostgreSQL、Redis、Keycloak、Ollama | 不启动 Java 和 Web 应用 |
| `docs/PROJECT_DESIGN.md` | 总体需求设计、模块边界、完整链路、高并发设计、运行验收与面试问答 | 按大需求组织，基线以当前工作区实际代码为准 |
| `docs/CORRECTNESS_REVIEW_FIXES.md` | 从初始需求到 Review、Bug 修复和验证的演进记录 | 记录失败场景、不变量、修复方式和明确未实现范围 |
| `.env.example` | 本地服务端环境变量模板 | 真实 `.env` 被 Git 忽略 |
| `.gitignore` | 排除密钥、IDE、构建产物 | 规则本身即说明排除对象 |
| `mvnw` / `mvnw.cmd` | Maven Wrapper 生成脚本 | 第三方生成文件，不手工插入业务注释 |
| `.mvn/wrapper/maven-wrapper.properties` | 固定 Maven 下载版本 | Maven Wrapper 标准配置 |

## 不支持注释的 JSON 文件

标准 JSON 不允许注释，强行添加会破坏解析，因此使用本索引解释。

| 文件 | 职责 |
| --- | --- |
| `infra/keycloak/buyforu-realm.json` | 本地 Keycloak Realm、OIDC Client、API audience、角色和 claim mapper |
| `web/package.json` | 前端依赖与 dev/build/typecheck 命令 |
| `web/package-lock.json` | npm 自动生成的精确依赖锁，不手工编辑 |
| `web/tsconfig.json` | TypeScript 严格模式、ES2022、Bundler 和 React JSX 配置 |
| `web/tsconfig.tsbuildinfo` | TypeScript 自动生成的增量编译缓存，可删除重建 |
| `scripts/accept.sh` | 本地一键验收 |
| `scripts/k6/` | 公平队列 / SSE / 读路径压测脚本 |
| `eval/rag/golden-set.json` | RAG 检索评估集，不是生产语料 |
| `Dockerfile.agent` / `Dockerfile.commerce` / `Dockerfile.web` | 运行镜像 |
| `.github/workflows/ci.yml` | 单测、集成测试、前端构建 |

## Agent 生产源码

| 目录/文件 | 职责 |
| --- | --- |
| `api/*` | JWT 用户入口、请求校验、错误协议、requestId |
| `api/CommandController.java` | 不暴露 payload 的命令状态查询 |
| `api/RunEventController.java` | JWT SSE、断点回放和心跳 |
| `concurrency/CommandService.java` | 准入、幂等、命令落库和调度索引 |
| `concurrency/CommandWorker.java` | 公平消费、短租约、恢复和状态事件 |
| `concurrency/RedisAdmissionController.java` | 分布式 Token Bucket 限流 |
| `concurrency/RedisFairQueue.java` | 用户等权虚拟时间队列 |
| `concurrency/DependencyExecutor.java` | 下游线程池、Bulkhead、超时与熔断 |
| `concurrency/InFlightCallRegistry.java` | 记下命令正在等待的下游 Future，取消时 cancel(true) |
| `config/SchedulingConfiguration.java` | dispatch / 租约心跳 / 维护任务分线程池 |
| `concurrency/RunLeaseRepository.java` | execution epoch 栅栏和崩溃租约恢复 |
| `application/GraphShoppingWorkflow.java` | 固定图启动、命令幂等和恢复门面 |
| `application/FixedShoppingGraph.java` | LangGraph4j 固定拓扑和人工中断点 |
| `application/ShoppingWorkflowService.java` | 规划、搜索、预占、审批、下单和三级 Replan 动作 |
| `domain/PlanSpec.java` | LLM 可输出的受限计划数据 |
| `domain/ShoppingAgentState.java` | 可持久化业务状态 |
| `domain/PlanSpecValidator.java` | JSON Schema 之后的业务安全校验 |
| `infrastructure/commerce/*` | MCP Adapter、SDK Client、服务凭证和调用审计 |
| `infrastructure/persistence/*` | PostgreSQL Run、记忆和带 execution epoch 的 checkpoint |
| `infrastructure/knowledge/*` | Embedding、切块、pgvector、召回和知识审计 |

## Commerce 生产源码

| 目录/文件 | 职责 |
| --- | --- |
| `commerce-port/*` | Agent 与 Commerce 共享的纯领域端口和模型 |
| `api/CommerceMcpTools.java` | 将 CommerceGateway 映射为结构化 MCP Tools |
| `api/McpSecurityConfiguration.java` | Agent 到 Commerce 的服务令牌保护 |
| `application/JdbcCommerceEngine.java` | 权威价格、优惠、库存、履约和订单事务 |
| `infrastructure/ReservationExpiryJob.java` | 到期预占库存回收 |
| `infrastructure/OutboxDispatcher.java` | 可靠事件投递、退避和死信 |
| `infrastructure/WebhookDomainEventPublisher.java` | production HMAC Webhook |

## Flyway SQL

已经执行的迁移禁止修改，包括“只加注释”，因为 Flyway checksum 会变化。逐文件说明分别位于：

- `agent-app/src/main/resources/db/migration/README.md`
- `commerce-service/src/main/resources/db/migration/README.md`

这两个 README 是历史 SQL 文件的注释伴随文档。未来新增的 SQL 迁移应在首次提交时直接包含文件头和关键段落注释。

## 测试源码

- `DeterministicPlanningModel`、`InMemoryAgentRunStore`、`InMemoryConversationMemory` 和 `InMemoryCommerceEngine` 都只位于 test 源集。
- 它们用于稳定模拟外部模型和交易系统，不是生产降级实现。
- 每个测试类顶部已注明验证的风险点；故障注入位置说明了模拟的崩溃窗口。
