package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.AgentMemoryJobState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AgentMemoryJobStateMapper {
    AgentMemoryJobState selectByAgentIdAndSessionId(
            @Param("agentId") String agentId,
            @Param("sessionId") String sessionId
    );

    int insert(AgentMemoryJobState state);

    int updateProgress(
            @Param("agentId") String agentId,
            @Param("sessionId") String sessionId,
            @Param("processedUserMessageCount") int processedUserMessageCount,
            @Param("lastProcessedMessageId") String lastProcessedMessageId,
            @Param("lastProcessedMessageCreatedAt") LocalDateTime lastProcessedMessageCreatedAt
    );
}
