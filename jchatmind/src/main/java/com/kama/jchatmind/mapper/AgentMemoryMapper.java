package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.AgentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentMemoryMapper {
    int insert(AgentMemory agentMemory);

    AgentMemory selectById(String id);

    List<AgentMemory> selectByAgentId(String agentId);

    List<AgentMemory> selectEnabledByAgentId(@Param("agentId") String agentId, @Param("limit") int limit);

    List<AgentMemory> selectRetrievedByAgentId(
            @Param("agentId") String agentId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    int countExactDuplicate(
            @Param("agentId") String agentId,
            @Param("memoryScope") String memoryScope,
            @Param("title") String title,
            @Param("content") String content
    );

    int markUsedByIds(@Param("ids") List<String> ids);

    int updateById(AgentMemory agentMemory);

    int deleteById(String id);
}
