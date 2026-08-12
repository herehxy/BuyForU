ALTER TABLE agent_schema.knowledge_chunk
    ALTER COLUMN embedding TYPE vector USING embedding::vector;

ALTER TABLE agent_schema.knowledge_document
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(255);

ALTER TABLE agent_schema.knowledge_document
    ADD COLUMN IF NOT EXISTS embedding_dimensions INTEGER;
