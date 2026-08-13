# Agent 数据库迁移说明

Flyway 已执行迁移的文件内容（包括注释）不能再修改，否则已有数据库会因 checksum 不一致拒绝启动。因此对每个历史 SQL 文件的说明集中维护在这里；新增迁移应在创建时直接包含文件头和关键 SQL 段落注释。

| 文件 | 作用 | 关键设计 |
| --- | --- | --- |
| `V1__agent_core.sql` | 建立 `agent_schema`、会话、消息、运行状态、业务 checkpoint、审批、Tool 审计及知识表 | `agent_run` 保存当前业务状态，`agent_checkpoint` 追加历史；知识向量初始声明为固定维度，后续迁移为模型维度无关类型 |
| `V2__langgraph_checkpoint.sql` | 建立 LangGraph4j 专用 checkpoint 表 | `(thread_id, checkpoint_id)` 唯一，`sequence_no` 保留执行顺序；它与业务 checkpoint 用途不同 |
| `V3__embedding_model.sql` | 将向量列改为不限固定维数，并记录模型名与维数 | 防止切换 Embedding 模型后静默混用不同向量空间 |
| `V4__approval_audit.sql` | 为审批增加用户、决定原因和操作者字段 | 待审批记录使用部分索引，便于恢复及过期处理 |
| `V5__knowledge_audit.sql` | 建立知识更新审计表 | 只保存正文 SHA-256 摘要，不复制完整知识正文 |
| `V6__concurrency_governance.sql` | 建立持久化命令、run 租约、execution epoch 和 SSE 事件日志 | PostgreSQL 保存事实；Redis 只保存可重建调度索引，`state_version + execution_epoch` 拒绝旧 Worker 写回 |
| `V7__command_ownership_and_idempotency.sql` | 幂等键改为 (user, run, key) | 同一把 key 不能重放到另一个购物任务 |

修改数据库结构时只能新增更高版本文件，例如 `V7__...sql`，不要回改上述文件。
