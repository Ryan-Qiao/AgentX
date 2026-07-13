package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.AgentRunCoordinator;
import com.kama.jchatmind.event.ChatEvent;
import lombok.AllArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class ChatEventListener {

    private final AgentRunCoordinator agentRunCoordinator;

    @EventListener
    public void handle(ChatEvent event) {
        agentRunCoordinator.submit(event);
    }
}
