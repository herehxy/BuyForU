-- tool_call 的保留清理索引。
-- 这张表原先没有任何保留策略（永久增长），加上审计清理任务后需要按 started_at 定位过期行；
-- 已有的 ix_tool_call_run_started 以 run_id 为前导列，无法服务只按 started_at 过滤的删除子查询。
CREATE INDEX IF NOT EXISTS ix_tool_call_retention
    ON agent_schema.tool_call(started_at);
