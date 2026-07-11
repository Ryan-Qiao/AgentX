package com.kama.jchatmind.trace;

import java.util.concurrent.atomic.AtomicInteger;

public final class AgentTraceContext {
    private final String traceId;
    private final String agentId;
    private final String sessionId;
    private final String userMessageId;
    private final AtomicInteger sequence = new AtomicInteger();

    public AgentTraceContext(String traceId, String agentId, String sessionId, String userMessageId) {
        this.traceId = traceId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.userMessageId = userMessageId;
    }

    public int nextSequence() {
        return sequence.incrementAndGet();
    }

    public String traceId() { return traceId; }
    public String agentId() { return agentId; }
    public String sessionId() { return sessionId; }
    public String userMessageId() { return userMessageId; }
}
