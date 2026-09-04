-- 高频查询索引：最近任务、对话上下文和 run 维度审批审计。
-- 使用 IF NOT EXISTS 便于已有开发库平滑升级，不修改任何业务数据。
CREATE INDEX IF NOT EXISTS ix_agent_run_user_recent
    ON agent_schema.agent_run(user_id, updated_at DESC, run_id DESC);

CREATE INDEX IF NOT EXISTS ix_message_conversation_recent
    ON agent_schema.message(conversation_id, role, created_at DESC, message_id DESC);

CREATE INDEX IF NOT EXISTS ix_approval_request_run
    ON agent_schema.approval_request(run_id, expires_at DESC);

CREATE INDEX IF NOT EXISTS ix_tool_call_run_started
    ON agent_schema.tool_call(run_id, started_at DESC);
