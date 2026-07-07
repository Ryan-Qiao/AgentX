ALTER TABLE agent
ADD COLUMN IF NOT EXISTS auto_memory_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent
ADD COLUMN IF NOT EXISTS auto_memory_interval INT NOT NULL DEFAULT 10;

CREATE TABLE IF NOT EXISTS agent_memory_job_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    processed_user_message_count INT NOT NULL DEFAULT 0,
    last_processed_message_id UUID REFERENCES chat_message(id) ON DELETE SET NULL,
    last_processed_message_created_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (agent_id, session_id)
);
