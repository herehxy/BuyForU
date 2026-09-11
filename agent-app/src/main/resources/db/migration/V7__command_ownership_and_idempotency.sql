-- 幂等键按 (用户, run, key) 隔离，避免同一把 key 重放到另一个购物任务。
ALTER TABLE agent_schema.agent_command
    DROP CONSTRAINT IF EXISTS uq_agent_command_user_idempotency;

ALTER TABLE agent_schema.agent_command
    ADD CONSTRAINT uq_agent_command_user_run_idempotency
        UNIQUE (user_id, run_id, idempotency_key);
