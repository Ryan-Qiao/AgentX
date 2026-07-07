package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AgentMemoryJobState {
    private String id;
    private String agentId;
    private String sessionId;
    private Integer processedUserMessageCount;
    private String lastProcessedMessageId;
    private LocalDateTime lastProcessedMessageCreatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
