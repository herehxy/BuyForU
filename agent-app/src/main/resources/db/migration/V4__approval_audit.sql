ALTER TABLE agent_schema.approval_request
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS decision_reason VARCHAR(255),
    ADD COLUMN IF NOT EXISTS decided_by VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_approval_run_pending
    ON agent_schema.approval_request(run_id, expires_at)
    WHERE decision IS NULL;
