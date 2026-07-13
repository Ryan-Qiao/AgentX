package com.kama.jchatmind.agent;

import com.kama.jchatmind.event.ChatEvent;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

@Slf4j
@Component
public class AgentRunCoordinator {
    private final JChatMindFactory factory;
    private final Executor executor;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionTails = new ConcurrentHashMap<>();

    public AgentRunCoordinator(JChatMindFactory factory, @Qualifier("agentExecutor") Executor executor) {
        this.factory = factory;
        this.executor = executor;
    }

    public void submit(ChatEvent event) {
        sessionTails.compute(event.getSessionId(), (sessionId, previous) -> {
            CompletableFuture<Void> base = previous == null
                    ? CompletableFuture.completedFuture(null)
                    : previous.handle((ignored, error) -> null);
            CompletableFuture<Void> next = base.thenRunAsync(() -> run(event), executor);
            next.whenComplete((ignored, error) -> sessionTails.remove(sessionId, next));
            return next;
        });
    }

    private void run(ChatEvent event) {
        MDC.put("traceId", event.getTraceId());
        MDC.put("agentId", event.getAgentId());
        MDC.put("sessionId", event.getSessionId());
        try {
            factory.create(event.getAgentId(), event.getSessionId(), event.getTraceId(), event.getUserMessageId()).run();
        } catch (Exception e) {
            log.error("Agent run failed before normal error handling", e);
        } finally {
            MDC.clear();
        }
    }
}
