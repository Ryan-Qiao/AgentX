package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.event.AutoMemoryEvent;
import com.kama.jchatmind.service.AutoAgentMemoryService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class AutoMemoryEventListener {
    private static final long INITIAL_DELAY_MILLIS = 3_000L;

    private final AutoAgentMemoryService autoAgentMemoryService;

    @Async("memoryExecutor")
    @EventListener
    public void handle(AutoMemoryEvent event) {
        try {
            Thread.sleep(INITIAL_DELAY_MILLIS);
            autoAgentMemoryService.consolidate(event.getAgentId(), event.getSessionId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("自动记忆任务被中断: agentId={}, sessionId={}", event.getAgentId(), event.getSessionId());
        } catch (Exception e) {
            log.warn("自动记忆任务执行失败，等待下次触发重试: agentId={}, sessionId={}, error={}",
                    event.getAgentId(),
                    event.getSessionId(),
                    e.getMessage(),
                    e);
        }
    }
}
