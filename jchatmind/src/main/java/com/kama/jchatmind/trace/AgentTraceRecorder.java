package com.kama.jchatmind.trace;

import java.time.Instant;
import java.util.Map;

public interface AgentTraceRecorder {
    void record(AgentTraceContext context,
                TraceEventType type,
                TraceEventStatus status,
                Integer stepNo,
                String name,
                Instant startedAt,
                Map<String, Object> payload,
                Throwable error);
}
