CREATE TABLE IF NOT EXISTS agent_schema.langgraph_checkpoint (
    thread_id VARCHAR(128) NOT NULL,
    checkpoint_id VARCHAR(128) NOT NULL,
    node_id VARCHAR(128),
    next_node_id VARCHAR(128),
    state JSONB NOT NULL,
    sequence_no BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (thread_id, checkpoint_id),
    UNIQUE (thread_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS ix_langgraph_checkpoint_latest
    ON agent_schema.langgraph_checkpoint (thread_id, sequence_no DESC);
