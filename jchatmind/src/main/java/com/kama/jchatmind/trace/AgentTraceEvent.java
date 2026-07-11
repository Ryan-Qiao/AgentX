package com.kama.jchatmind.trace;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
public class AgentTraceEvent {
    @Builder.Default
    private String schemaVersion = "1.0";
    @Builder.Default
    private String category = "agent_trace";
    private Instant timestamp;
    private String eventId;
    private String traceId;
    private Integer sequenceNo;
    private Integer stepNo;
    private TraceEventType eventType;
    private String eventName;
    private TraceEventStatus status;
    private String agentId;
    private String sessionId;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private Map<String, Object> payload;
    private Map<String, Object> error;
    private Map<String, Object> metadata;
}
