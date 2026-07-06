package com.kama.jchatmind.converter;

import com.kama.jchatmind.model.dto.AgentMemoryDTO;
import com.kama.jchatmind.model.entity.AgentMemory;
import com.kama.jchatmind.model.request.CreateAgentMemoryRequest;
import com.kama.jchatmind.model.request.UpdateAgentMemoryRequest;
import com.kama.jchatmind.model.vo.AgentMemoryVO;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
public class AgentMemoryConverter {

    public AgentMemory toEntity(AgentMemoryDTO dto) {
        Assert.notNull(dto, "AgentMemoryDTO cannot be null");
        return AgentMemory.builder()
                .id(dto.getId())
                .agentId(dto.getAgentId())
                .sourceMessageId(dto.getSourceMessageId())
                .memoryScope(dto.getMemoryScope())
                .memoryType(dto.getMemoryType())
                .title(dto.getTitle())
                .content(dto.getContent())
                .embedding(dto.getEmbedding())
                .priority(dto.getPriority())
                .enabled(dto.getEnabled())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .lastUsedAt(dto.getLastUsedAt())
                .build();
    }

    public AgentMemoryDTO toDTO(AgentMemory entity) {
        Assert.notNull(entity, "AgentMemory cannot be null");
        return AgentMemoryDTO.builder()
                .id(entity.getId())
                .agentId(entity.getAgentId())
                .sourceMessageId(entity.getSourceMessageId())
                .memoryScope(entity.getMemoryScope())
                .memoryType(entity.getMemoryType())
                .title(entity.getTitle())
                .content(entity.getContent())
                .embedding(entity.getEmbedding())
                .priority(entity.getPriority())
                .enabled(entity.getEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .lastUsedAt(entity.getLastUsedAt())
                .build();
    }

    public AgentMemoryDTO toDTO(String agentId, CreateAgentMemoryRequest request) {
        Assert.notNull(agentId, "AgentId cannot be null");
        Assert.notNull(request, "CreateAgentMemoryRequest cannot be null");
        return AgentMemoryDTO.builder()
                .agentId(agentId)
                .sourceMessageId(request.getSourceMessageId())
                .memoryScope(request.getMemoryScope())
                .memoryType(request.getMemoryType())
                .title(request.getTitle())
                .content(request.getContent())
                .priority(request.getPriority())
                .enabled(request.getEnabled())
                .build();
    }

    public AgentMemoryVO toVO(AgentMemoryDTO dto) {
        Assert.notNull(dto, "AgentMemoryDTO cannot be null");
        return AgentMemoryVO.builder()
                .id(dto.getId())
                .agentId(dto.getAgentId())
                .sourceMessageId(dto.getSourceMessageId())
                .memoryScope(dto.getMemoryScope())
                .memoryType(dto.getMemoryType())
                .title(dto.getTitle())
                .content(dto.getContent())
                .priority(dto.getPriority())
                .enabled(dto.getEnabled())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .lastUsedAt(dto.getLastUsedAt())
                .build();
    }

    public AgentMemoryVO toVO(AgentMemory entity) {
        return toVO(toDTO(entity));
    }

    public void updateDTOFromRequest(AgentMemoryDTO dto, UpdateAgentMemoryRequest request) {
        Assert.notNull(dto, "AgentMemoryDTO cannot be null");
        Assert.notNull(request, "UpdateAgentMemoryRequest cannot be null");

        if (request.getMemoryType() != null) {
            dto.setMemoryType(request.getMemoryType());
        }
        if (request.getMemoryScope() != null) {
            dto.setMemoryScope(request.getMemoryScope());
        }
        if (request.getTitle() != null) {
            dto.setTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            dto.setContent(request.getContent());
        }
        if (request.getPriority() != null) {
            dto.setPriority(request.getPriority());
        }
        if (request.getEnabled() != null) {
            dto.setEnabled(request.getEnabled());
        }
    }
}
