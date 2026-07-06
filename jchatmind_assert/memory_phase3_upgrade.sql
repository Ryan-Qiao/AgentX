CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE agent_memory
ADD COLUMN IF NOT EXISTS embedding VECTOR(1024);

CREATE INDEX IF NOT EXISTS idx_agent_memory_embedding
ON agent_memory
USING ivfflat (embedding vector_l2_ops)
WITH (lists = 100);
