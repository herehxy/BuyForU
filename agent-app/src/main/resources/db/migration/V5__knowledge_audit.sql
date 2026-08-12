CREATE TABLE IF NOT EXISTS agent_schema.knowledge_audit (
    audit_id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    version VARCHAR(64) NOT NULL,
    actor_user_id VARCHAR(64) NOT NULL,
    source_uri TEXT NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_knowledge_audit_document
    ON agent_schema.knowledge_audit(document_id, created_at DESC);
