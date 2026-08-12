CREATE SCHEMA IF NOT EXISTS agent_schema;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS agent_schema.conversation (
    conversation_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_schema.message (
    message_id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES agent_schema.conversation(conversation_id),
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_schema.agent_run (
    run_id VARCHAR(64) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    phase VARCHAR(64) NOT NULL,
    state JSONB NOT NULL,
    plan_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_schema.agent_checkpoint (
    run_id VARCHAR(64) NOT NULL,
    checkpoint_version BIGINT NOT NULL,
    state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, checkpoint_version)
);

CREATE TABLE IF NOT EXISTS agent_schema.approval_request (
    approval_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    summary_hash VARCHAR(64) NOT NULL,
    decision VARCHAR(16),
    expires_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS agent_schema.tool_call (
    tool_call_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    effect_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    request_digest VARCHAR(64),
    response_digest VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS agent_schema.knowledge_document (
    document_id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    source_uri TEXT NOT NULL,
    version VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_schema.knowledge_chunk (
    chunk_id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL REFERENCES agent_schema.knowledge_document(document_id),
    content TEXT NOT NULL,
    embedding vector(1536),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

