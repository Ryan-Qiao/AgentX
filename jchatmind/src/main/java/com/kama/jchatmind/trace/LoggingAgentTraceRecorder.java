package com.kama.jchatmind.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class LoggingAgentTraceRecorder implements AgentTraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(LoggingAgentTraceRecorder.class);
    private static final Logger traceLog = LoggerFactory.getLogger("AGENT_TRACE");

    private final ObjectMapper objectMapper;
    private final int maxFieldChars;

    public LoggingAgentTraceRecorder(ObjectMapper objectMapper,
                                     @Value("${agent-trace.max-field-chars:20000}") int maxFieldChars) {
        this.objectMapper = objectMapper;
        this.maxFieldChars = Math.max(1000, maxFieldChars);
    }

    @Override
    public void record(AgentTraceContext context,
                       TraceEventType type,
                       TraceEventStatus status,
                       Integer stepNo,
                       String name,
                       Instant startedAt,
                       Map<String, Object> payload,
                       Throwable error) {
        try {
            Instant now = Instant.now();
            AgentTraceEvent event = AgentTraceEvent.builder()
                    .timestamp(now)
                    .eventId(UUID.randomUUID().toString())
                    .traceId(context.traceId())
                    .sequenceNo(context.nextSequence())
                    .stepNo(stepNo)
                    .eventType(type)
                    .eventName(name)
                    .status(status)
                    .agentId(context.agentId())
                    .sessionId(context.sessionId())
                    .startedAt(startedAt)
                    .completedAt(status == TraceEventStatus.STARTED ? null : now)
                    .durationMs(status == TraceEventStatus.STARTED || startedAt == null
                            ? null
                            : Math.max(0, Duration.between(startedAt, now).toMillis()))
                    .payload(truncateMap(payload))
                    .error(toError(error))
                    .metadata(Map.of("userMessageId", context.userMessageId()))
                    .build();
            traceLog.info(objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to emit agent trace event: traceId={}, eventType={}",
                    context.traceId(), type, e);
        }
    }

    private Map<String, Object> toError(Throwable error) {
        if (error == null) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", error.getClass().getName());
        result.put("message", truncate(error.getMessage()));
        return result;
    }

    private Map<String, Object> truncateMap(Map<String, Object> value) throws JsonProcessingException {
        if (value == null) return null;
        String json = objectMapper.writeValueAsString(value);
        if (json.length() <= maxFieldChars) return value;
        return Map.of(
                "content", json.substring(0, maxFieldChars),
                "originalSize", json.length(),
                "truncated", true
        );
    }

    private String truncate(String value) {
        if (value == null || value.length() <= maxFieldChars) return value;
        return value.substring(0, maxFieldChars);
    }
}
