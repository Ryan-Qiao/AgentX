package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.converter.AgentMemoryConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.AgentMemoryMapper;
import com.kama.jchatmind.model.dto.AgentMemoryDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.AgentMemory;
import com.kama.jchatmind.model.request.CreateAgentMemoryRequest;
import com.kama.jchatmind.model.request.UpdateAgentMemoryRequest;
import com.kama.jchatmind.model.response.CreateAgentMemoryResponse;
import com.kama.jchatmind.model.response.GetAgentMemoriesResponse;
import com.kama.jchatmind.model.vo.AgentMemoryVO;
import com.kama.jchatmind.service.AgentMemoryFacadeService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class AgentMemoryFacadeServiceImpl implements AgentMemoryFacadeService {
    private static final String DEFAULT_MEMORY_SCOPE = "core";
    private static final String DEFAULT_MEMORY_TYPE = "fact";
    private static final int DEFAULT_PRIORITY = 0;
    private static final String RETRIEVED_MEMORY_SCOPE = "retrieved";

    private final AgentMemoryMapper agentMemoryMapper;
    private final AgentMapper agentMapper;
    private final AgentMemoryConverter agentMemoryConverter;
    private final RagService ragService;

    @Override
    public GetAgentMemoriesResponse getAgentMemoriesByAgentId(String agentId) {
        assertAgentExists(agentId);
        List<AgentMemory> memories = agentMemoryMapper.selectByAgentId(agentId);
        List<AgentMemoryVO> result = new ArrayList<>();
        for (AgentMemory memory : memories) {
            result.add(agentMemoryConverter.toVO(memory));
        }
        return GetAgentMemoriesResponse.builder()
                .agentMemories(result.toArray(new AgentMemoryVO[0]))
                .build();
    }

    @Override
    public List<AgentMemoryDTO> getEnabledAgentMemories(String agentId, int limit) {
        if (!StringUtils.hasText(agentId)) {
            return List.of();
        }
        List<AgentMemory> memories = agentMemoryMapper.selectEnabledByAgentId(agentId, limit);
        List<String> usedMemoryIds = memories.stream()
                .map(AgentMemory::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (!usedMemoryIds.isEmpty()) {
            agentMemoryMapper.markUsedByIds(usedMemoryIds);
        }
        return memories
                .stream()
                .map(agentMemoryConverter::toDTO)
                .toList();
    }

    @Override
    public List<AgentMemoryDTO> getRetrievedAgentMemories(String agentId, String query, int limit) {
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(query) || limit <= 0) {
            return List.of();
        }
        try {
            String vectorLiteral = toPgVector(ragService.embed(query));
            List<AgentMemory> memories = agentMemoryMapper.selectRetrievedByAgentId(agentId, vectorLiteral, limit);
            markUsed(memories);
            return memories.stream()
                    .map(agentMemoryConverter::toDTO)
                    .toList();
        } catch (Exception e) {
            log.warn("召回 Agent Retrieved Memory 失败，降级为空: agentId={}, error={}", agentId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public CreateAgentMemoryResponse createAgentMemory(String agentId, CreateAgentMemoryRequest request) {
        assertAgentExists(agentId);
        validateCreateRequest(request);

        AgentMemoryDTO dto = agentMemoryConverter.toDTO(agentId, request);
        normalizeDefaults(dto);

        LocalDateTime now = LocalDateTime.now();
        dto.setCreatedAt(now);
        dto.setUpdatedAt(now);
        fillEmbeddingIfNeeded(dto);

        AgentMemory entity = agentMemoryConverter.toEntity(dto);
        int result = agentMemoryMapper.insert(entity);
        if (result <= 0) {
            throw new BizException("创建 Agent 记忆失败");
        }
        return CreateAgentMemoryResponse.builder()
                .agentMemoryId(entity.getId())
                .build();
    }

    @Override
    public void updateAgentMemory(String memoryId, UpdateAgentMemoryRequest request) {
        AgentMemory existing = agentMemoryMapper.selectById(memoryId);
        if (existing == null) {
            throw new BizException("Agent 记忆不存在: " + memoryId);
        }

        AgentMemoryDTO dto = agentMemoryConverter.toDTO(existing);
        agentMemoryConverter.updateDTOFromRequest(dto, request);
        normalizeDefaults(dto);
        fillEmbeddingIfNeeded(dto);

        AgentMemory updated = agentMemoryConverter.toEntity(dto);
        updated.setId(existing.getId());
        int result = agentMemoryMapper.updateById(updated);
        if (result <= 0) {
            throw new BizException("更新 Agent 记忆失败");
        }
    }

    @Override
    public void deleteAgentMemory(String memoryId) {
        AgentMemory existing = agentMemoryMapper.selectById(memoryId);
        if (existing == null) {
            throw new BizException("Agent 记忆不存在: " + memoryId);
        }
        int result = agentMemoryMapper.deleteById(memoryId);
        if (result <= 0) {
            throw new BizException("删除 Agent 记忆失败");
        }
    }

    private void assertAgentExists(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new BizException("Agent ID 不能为空");
        }
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BizException("Agent 不存在: " + agentId);
        }
    }

    private void validateCreateRequest(CreateAgentMemoryRequest request) {
        if (request == null) {
            throw new BizException("Agent 记忆请求不能为空");
        }
        if (!StringUtils.hasText(request.getTitle())) {
            throw new BizException("Agent 记忆标题不能为空");
        }
        if (!StringUtils.hasText(request.getContent())) {
            throw new BizException("Agent 记忆内容不能为空");
        }
    }

    private void validateMemory(AgentMemoryDTO dto) {
        if (!StringUtils.hasText(dto.getTitle())) {
            throw new BizException("Agent 记忆标题不能为空");
        }
        if (!StringUtils.hasText(dto.getContent())) {
            throw new BizException("Agent 记忆内容不能为空");
        }
        if (!"core".equals(dto.getMemoryScope()) && !RETRIEVED_MEMORY_SCOPE.equals(dto.getMemoryScope())) {
            throw new BizException("Agent 记忆范围仅支持 core / retrieved");
        }
    }

    private void normalizeDefaults(AgentMemoryDTO dto) {
        if (!StringUtils.hasText(dto.getMemoryScope())) {
            dto.setMemoryScope(DEFAULT_MEMORY_SCOPE);
        }
        if (!StringUtils.hasText(dto.getMemoryType())) {
            dto.setMemoryType(DEFAULT_MEMORY_TYPE);
        }
        if (dto.getPriority() == null) {
            dto.setPriority(DEFAULT_PRIORITY);
        }
        if (dto.getEnabled() == null) {
            dto.setEnabled(true);
        }
        dto.setTitle(dto.getTitle().trim());
        dto.setContent(dto.getContent().trim());
        dto.setMemoryScope(dto.getMemoryScope().trim());
        dto.setMemoryType(dto.getMemoryType().trim());
        validateMemory(dto);
    }

    private void fillEmbeddingIfNeeded(AgentMemoryDTO dto) {
        if (!RETRIEVED_MEMORY_SCOPE.equals(dto.getMemoryScope())) {
            dto.setEmbedding(null);
            return;
        }
        String embeddingText = "%s\n%s".formatted(dto.getTitle(), dto.getContent());
        try {
            dto.setEmbedding(ragService.embed(embeddingText));
        } catch (Exception e) {
            throw new BizException("生成 Agent Retrieved Memory 向量失败: " + e.getMessage());
        }
    }

    private void markUsed(List<AgentMemory> memories) {
        List<String> usedMemoryIds = memories.stream()
                .map(AgentMemory::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (!usedMemoryIds.isEmpty()) {
            agentMemoryMapper.markUsedByIds(usedMemoryIds);
        }
    }

    private String toPgVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new BizException("Embedding 结果为空");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
