package com.kama.jchatmind.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AutoMemoryEvent {
    private String agentId;
    private String sessionId;
}
