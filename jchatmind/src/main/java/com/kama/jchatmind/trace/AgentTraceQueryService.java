package com.kama.jchatmind.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class AgentTraceQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentTraceQueryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AgentTraceDetailResponse getByTraceId(String traceId) {
        List<AgentTraceDetailResponse.TraceSummary> summaries = jdbcTemplate.query("""
                SELECT id, agent_id, session_id, user_message_id, assistant_message_id,
                       status, finish_reason, model_name, total_steps, total_model_calls,
                       total_tool_calls, trace_incomplete, started_at, completed_at,
                       duration_ms, error_message
                FROM agent_trace WHERE id = ?
                """, (rs, rowNum) -> mapSummary(rs), traceId);
        if (summaries.isEmpty()) {
            throw new BizException("Trace 不存在或尚未采集: " + traceId);
        }
        List<AgentTraceDetailResponse.TraceEventDetail> events = jdbcTemplate.query("""
                SELECT event_id, sequence_no, step_no, event_type, event_name, status,
                       occurred_at, duration_ms, payload::text, error::text, metadata::text
                FROM agent_trace_event WHERE trace_id = ? ORDER BY sequence_no ASC
                """, (rs, rowNum) -> mapEvent(rs), traceId);
        return AgentTraceDetailResponse.builder().trace(summaries.get(0)).events(events).build();
    }

    private AgentTraceDetailResponse.TraceSummary mapSummary(ResultSet rs) throws SQLException {
        return AgentTraceDetailResponse.TraceSummary.builder()
                .id(rs.getString("id"))
                .agentId(rs.getString("agent_id"))
                .sessionId(rs.getString("session_id"))
                .userMessageId(rs.getString("user_message_id"))
                .assistantMessageId(rs.getString("assistant_message_id"))
                .status(rs.getString("status"))
                .finishReason(rs.getString("finish_reason"))
                .modelName(rs.getString("model_name"))
                .totalSteps(rs.getInt("total_steps"))
                .totalModelCalls(rs.getInt("total_model_calls"))
                .totalToolCalls(rs.getInt("total_tool_calls"))
                .traceIncomplete(rs.getBoolean("trace_incomplete"))
                .startedAt(rs.getObject("started_at", OffsetDateTime.class))
                .completedAt(rs.getObject("completed_at", OffsetDateTime.class))
                .durationMs((Long) rs.getObject("duration_ms"))
                .errorMessage(rs.getString("error_message"))
                .build();
    }

    private AgentTraceDetailResponse.TraceEventDetail mapEvent(ResultSet rs) throws SQLException {
        return AgentTraceDetailResponse.TraceEventDetail.builder()
                .eventId(rs.getString("event_id"))
                .sequenceNo(rs.getInt("sequence_no"))
                .stepNo((Integer) rs.getObject("step_no"))
                .eventType(rs.getString("event_type"))
                .eventName(rs.getString("event_name"))
                .status(rs.getString("status"))
                .occurredAt(rs.getObject("occurred_at", OffsetDateTime.class))
                .durationMs((Long) rs.getObject("duration_ms"))
                .payload(readMap(rs.getString("payload")))
                .error(readMap(rs.getString("error")))
                .metadata(readMap(rs.getString("metadata")))
                .build();
    }

    private Map<String, Object> readMap(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.singletonMap("raw", json);
        }
    }
}
