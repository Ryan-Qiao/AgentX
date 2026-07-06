package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.converter.AgentMemoryConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.AgentMemoryMapper;
import com.kama.jchatmind.model.dto.AgentMemoryDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.AgentMemory;
import com.kama.jchatmind.model.request.CreateAgentMemoryRequest;
import com.kama.jchatmind.service.RagService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentMemoryFacadeServiceImplTest {

    @Test
    void createRetrievedMemoryGeneratesEmbeddingBeforeInsert() {
        AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        RagService ragService = mock(RagService.class);
        AgentMemoryFacadeServiceImpl service = new AgentMemoryFacadeServiceImpl(
                memoryMapper,
                agentMapper,
                new AgentMemoryConverter(),
                ragService
        );

        when(agentMapper.selectById("agent-1")).thenReturn(Agent.builder().id("agent-1").build());
        when(ragService.embed("召回规则\n用户问到索引时优先解释 B+ 树。")).thenReturn(new float[]{0.1f, 0.2f});
        when(memoryMapper.insert(any(AgentMemory.class))).thenReturn(1);

        CreateAgentMemoryRequest request = new CreateAgentMemoryRequest();
        request.setMemoryScope("retrieved");
        request.setTitle("召回规则");
        request.setContent("用户问到索引时优先解释 B+ 树。");

        service.createAgentMemory("agent-1", request);

        ArgumentCaptor<AgentMemory> captor = ArgumentCaptor.forClass(AgentMemory.class);
        verify(memoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getMemoryScope()).isEqualTo("retrieved");
        assertThat(captor.getValue().getEmbedding()).containsExactly(0.1f, 0.2f);
    }

    @Test
    void retrievesSemanticMemoriesByQueryEmbedding() {
        AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);
        AgentMapper agentMapper = mock(AgentMapper.class);
        RagService ragService = mock(RagService.class);
        AgentMemoryFacadeServiceImpl service = new AgentMemoryFacadeServiceImpl(
                memoryMapper,
                agentMapper,
                new AgentMemoryConverter(),
                ragService
        );

        when(ragService.embed("索引怎么优化")).thenReturn(new float[]{0.3f, 0.4f});
        when(memoryMapper.selectRetrievedByAgentId(eq("agent-1"), eq("[0.3,0.4]"), eq(3)))
                .thenReturn(List.of(AgentMemory.builder()
                        .id("memory-1")
                        .agentId("agent-1")
                        .memoryScope("retrieved")
                        .memoryType("fact")
                        .title("索引优化")
                        .content("优先解释联合索引和最左前缀。")
                        .build()));

        List<AgentMemoryDTO> memories = service.getRetrievedAgentMemories("agent-1", "索引怎么优化", 3);

        assertThat(memories).hasSize(1);
        assertThat(memories.get(0).getTitle()).isEqualTo("索引优化");
        verify(memoryMapper).markUsedByIds(List.of("memory-1"));
    }
}
