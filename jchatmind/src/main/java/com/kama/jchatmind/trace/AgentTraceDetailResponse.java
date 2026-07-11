package com.kama.jchatmind.trace;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class AgentTraceDetailResponse {
    private TraceSummary trace;
    private List<TraceEventDetail> events;

    @Data
    @Builder
    public static class TraceSummary {
        private String id;
        private String agentId;
        private String sessionId;
        private String userMessageId;
        private String assistantMessageId;
        private String status;
        private String finishReason;
        private String modelName;
        private Integer totalSteps;
        private Integer totalModelCalls;
        private Integer totalToolCalls;
        private Boolean traceIncomplete;
        private OffsetDateTime startedAt;
        private OffsetDateTime completedAt;
        private Long durationMs;
        private String errorMessage;
    }

    @Data
    @Builder
    public static class TraceEventDetail {
        private String eventId;
        private Integer sequenceNo;
        private Integer stepNo;
        private String eventType;
        private String eventName;
        private String status;
        private OffsetDateTime occurredAt;
        private Long durationMs;
        private Map<String, Object> payload;
        private Map<String, Object> error;
        private Map<String, Object> metadata;
    }
}
