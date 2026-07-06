package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.AgentMemoryDTO;
import com.kama.jchatmind.model.request.CreateAgentMemoryRequest;
import com.kama.jchatmind.model.request.UpdateAgentMemoryRequest;
import com.kama.jchatmind.model.response.CreateAgentMemoryResponse;
import com.kama.jchatmind.model.response.GetAgentMemoriesResponse;

import java.util.List;

public interface AgentMemoryFacadeService {
    GetAgentMemoriesResponse getAgentMemoriesByAgentId(String agentId);

    List<AgentMemoryDTO> getEnabledAgentMemories(String agentId, int limit);

    List<AgentMemoryDTO> getRetrievedAgentMemories(String agentId, String query, int limit);

    CreateAgentMemoryResponse createAgentMemory(String agentId, CreateAgentMemoryRequest request);

    void updateAgentMemory(String memoryId, UpdateAgentMemoryRequest request);

    void deleteAgentMemory(String memoryId);
}
