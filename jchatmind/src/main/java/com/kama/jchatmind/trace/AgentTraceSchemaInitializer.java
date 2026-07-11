package com.kama.jchatmind.trace;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgentTraceSchemaInitializer {
    private final JdbcTemplate jdbcTemplate;

    public AgentTraceSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_trace (
                    id VARCHAR(36) PRIMARY KEY,
                    agent_id UUID,
                    session_id UUID,
                    user_message_id UUID,
                    assistant_message_id UUID,
                    status VARCHAR(20) NOT NULL,
                    finish_reason VARCHAR(30),
                    model_name VARCHAR(100),
                    total_steps INT NOT NULL DEFAULT 0,
                    total_model_calls INT NOT NULL DEFAULT 0,
                    total_tool_calls INT NOT NULL DEFAULT 0,
                    prompt_tokens INT,
                    completion_tokens INT,
                    first_sequence_no INT,
                    last_sequence_no INT,
                    received_event_count INT NOT NULL DEFAULT 0,
                    trace_incomplete BOOLEAN NOT NULL DEFAULT FALSE,
                    started_at TIMESTAMPTZ,
                    completed_at TIMESTAMPTZ,
                    duration_ms BIGINT,
                    error_message TEXT,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_trace_event (
                    event_id VARCHAR(36) PRIMARY KEY,
                    trace_id VARCHAR(36) NOT NULL,
                    schema_version VARCHAR(10) NOT NULL,
                    sequence_no INT NOT NULL,
                    step_no INT,
                    event_type VARCHAR(40) NOT NULL,
                    event_name VARCHAR(200),
                    status VARCHAR(20) NOT NULL,
                    occurred_at TIMESTAMPTZ NOT NULL,
                    started_at TIMESTAMPTZ,
                    completed_at TIMESTAMPTZ,
                    duration_ms BIGINT,
                    payload JSONB,
                    error JSONB,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE (trace_id, sequence_no)
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_trace_event_trace_sequence ON agent_trace_event(trace_id, sequence_no)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_agent_trace_session_id ON agent_trace(session_id)");
    }
}
