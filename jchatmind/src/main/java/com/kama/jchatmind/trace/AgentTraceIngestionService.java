package com.kama.jchatmind.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

@Service
public class AgentTraceIngestionService {
    private static final Logger log = LoggerFactory.getLogger(AgentTraceIngestionService.class);
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public AgentTraceIngestionService(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void ingest(String json) {
        try {
            AgentTraceEvent event = objectMapper.readValue(json, AgentTraceEvent.class);
            if (!"1.0".equals(event.getSchemaVersion()) || !"agent_trace".equals(event.getCategory())) {
                log.warn("Ignoring unsupported agent trace event: schemaVersion={}, category={}",
                        event.getSchemaVersion(), event.getCategory());
                return;
            }
            String payload = event.getPayload() == null ? null : objectMapper.writeValueAsString(event.getPayload());
            String error = event.getError() == null ? null : objectMapper.writeValueAsString(event.getError());
            String metadata = event.getMetadata() == null ? "{}" : objectMapper.writeValueAsString(event.getMetadata());

            int inserted = jdbcTemplate.update("""
                    INSERT INTO agent_trace_event (
                        event_id, trace_id, schema_version, sequence_no, step_no,
                        event_type, event_name, status, occurred_at, started_at,
                        completed_at, duration_ms, payload, error, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb))
                    ON CONFLICT DO NOTHING
                    """,
                    event.getEventId(), event.getTraceId(), event.getSchemaVersion(), event.getSequenceNo(), event.getStepNo(),
                    event.getEventType().name(), event.getEventName(), event.getStatus().name(),
                    timestamp(event.getTimestamp()), timestamp(event.getStartedAt()), timestamp(event.getCompletedAt()), event.getDurationMs(),
                    payload, error, metadata);
            if (inserted == 1) updateProjection(event);
        } catch (Exception e) {
            log.warn("Failed to ingest agent trace log", e);
        }
    }

    private void updateProjection(AgentTraceEvent event) throws Exception {
        String metadata = event.getMetadata() == null ? "{}" : objectMapper.writeValueAsString(event.getMetadata());
        jdbcTemplate.update("""
                INSERT INTO agent_trace (
                    id, agent_id, session_id, user_message_id, status,
                    first_sequence_no, last_sequence_no, received_event_count,
                    started_at, metadata, created_at, updated_at
                ) VALUES (?, CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'RUNNING', ?, ?, 1, ?, CAST(? AS jsonb), NOW(), NOW())
                ON CONFLICT (id) DO UPDATE SET
                    first_sequence_no = LEAST(agent_trace.first_sequence_no, EXCLUDED.first_sequence_no),
                    last_sequence_no = GREATEST(agent_trace.last_sequence_no, EXCLUDED.last_sequence_no),
                    received_event_count = agent_trace.received_event_count + 1,
                    updated_at = NOW()
                """,
                event.getTraceId(), event.getAgentId(), event.getSessionId(),
                event.getMetadata() == null ? null : event.getMetadata().get("userMessageId"),
                event.getSequenceNo(), event.getSequenceNo(), timestamp(event.getTimestamp()), metadata);

        switch (event.getEventType()) {
            case RUN_STARTED -> updateRunStarted(event);
            case MODEL_CALL_COMPLETED, MODEL_CALL_FAILED -> jdbcTemplate.update(
                    "UPDATE agent_trace SET total_model_calls = total_model_calls + 1, updated_at = NOW() WHERE id = ?",
                    event.getTraceId());
            case TOOL_CALL_COMPLETED, TOOL_CALL_FAILED -> jdbcTemplate.update(
                    "UPDATE agent_trace SET total_tool_calls = total_tool_calls + 1, updated_at = NOW() WHERE id = ?",
                    event.getTraceId());
            case STEP_COMPLETED -> jdbcTemplate.update(
                    "UPDATE agent_trace SET total_steps = GREATEST(total_steps, ?), updated_at = NOW() WHERE id = ?",
                    event.getStepNo(), event.getTraceId());
            case RUN_COMPLETED -> finish(event, "COMPLETED");
            case RUN_FAILED -> finish(event, "FAILED");
            default -> { }
        }
        jdbcTemplate.update("""
                UPDATE agent_trace
                SET trace_incomplete = received_event_count <> (last_sequence_no - first_sequence_no + 1)
                WHERE id = ?
                """, event.getTraceId());
    }

    private void updateRunStarted(AgentTraceEvent event) {
        Object model = event.getPayload() == null ? null : event.getPayload().get("model");
        jdbcTemplate.update("""
                UPDATE agent_trace SET model_name = ?, started_at = ?, updated_at = NOW() WHERE id = ?
                """, model, timestamp(event.getTimestamp()), event.getTraceId());
    }

    private void finish(AgentTraceEvent event, String status) {
        Object reason = event.getPayload() == null ? null : event.getPayload().get("finishReason");
        Object assistantMessageId = event.getPayload() == null ? null : event.getPayload().get("assistantMessageId");
        String errorMessage = event.getError() == null ? null : String.valueOf(event.getError().get("message"));
        jdbcTemplate.update("""
                UPDATE agent_trace SET status = ?, finish_reason = ?, assistant_message_id = CAST(? AS uuid),
                    completed_at = ?, duration_ms = ?, error_message = ?, updated_at = NOW()
                WHERE id = ?
                """, status, reason, assistantMessageId, timestamp(event.getTimestamp()), event.getDurationMs(), errorMessage, event.getTraceId());
    }

    private Timestamp timestamp(java.time.Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
