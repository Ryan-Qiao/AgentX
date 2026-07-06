package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AgentMemory {
    private String id;
    private String agentId;
    private String sourceMessageId;
    private String memoryScope;
    private String memoryType;
    private String title;
    private String content;
    private float[] embedding;
    private Integer priority;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastUsedAt;
}
