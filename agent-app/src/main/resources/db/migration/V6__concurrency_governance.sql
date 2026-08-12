-- Durable command queue, per-run leases/fencing and resumable SSE event log.
ALTER TABLE agent_schema.agent_run
    ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS agent_schema.agent_command (
    command_id UUID PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    queue_class VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deadline_at TIMESTAMPTZ NOT NULL,
    execution_epoch BIGINT,
    result_state_version BIGINT,
    error_code VARCHAR(64),
    error_detail VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_agent_command_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_agent_command_status CHECK (status IN
        ('QUEUED','RUNNING','RETRY_WAIT','WAITING_USER','SUCCEEDED','FAILED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_agent_command_queue CHECK (queue_class IN ('PLANNING','TRANSACTION','CONTROL'))
);

CREATE INDEX IF NOT EXISTS ix_agent_command_dispatch
    ON agent_schema.agent_command(status, queue_class, available_at, created_at);
CREATE INDEX IF NOT EXISTS ix_agent_command_run
    ON agent_schema.agent_command(run_id, created_at);

CREATE TABLE IF NOT EXISTS agent_schema.agent_run_execution (
    run_id VARCHAR(64) PRIMARY KEY,
    execution_epoch BIGINT NOT NULL DEFAULT 0,
    active_command_id UUID REFERENCES agent_schema.agent_command(command_id),
    lease_owner VARCHAR(160),
    lease_until TIMESTAMPTZ,
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_agent_run_execution_lease
    ON agent_schema.agent_run_execution(lease_until) WHERE active_command_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS agent_schema.agent_run_event (
    event_id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    command_id UUID,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_agent_run_event_replay
    ON agent_schema.agent_run_event(run_id, event_id);
CREATE INDEX IF NOT EXISTS ix_agent_run_event_retention
    ON agent_schema.agent_run_event(created_at);

ALTER TABLE agent_schema.langgraph_checkpoint
    ADD COLUMN IF NOT EXISTS execution_epoch BIGINT;
