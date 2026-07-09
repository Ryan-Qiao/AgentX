package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.AgentMemoryJobStateMapper;
import com.kama.jchatmind.mapper.AgentMemoryMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.entity.AgentMemory;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.request.CreateAgentMemoryRequest;
import com.kama.jchatmind.model.request.CreateUserMemoryRequest;
import com.kama.jchatmind.model.request.UpdateUserMemoryRequest;
import com.kama.jchatmind.service.AgentMemoryFacadeService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AutoAgentMemoryServiceImplTest {

    @Test
    void shouldRouteModelSelectedTargetsToUserAndAgentMemory() throws Exception {
        AgentMemoryMapper agentMemoryMapper = mock(AgentMemoryMapper.class);
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        AgentMemoryFacadeService agentMemoryFacadeService = mock(AgentMemoryFacadeService.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);

        when(agentMemoryMapper.countExactDuplicate(
                "agent-1",
                "core",
                "面试复盘助手",
                "当前 Agent 主要用于帮助用户做后端面试复盘。"
        )).thenReturn(0);
        when(userMemoryMapper.countExactDuplicate(
                "代码示例偏好",
                "用户偏好代码问题使用 Java 示例回答。"
        )).thenReturn(0);

        AutoAgentMemoryServiceImpl service = newService(
                agentMemoryMapper,
                userMemoryMapper,
                agentMemoryFacadeService,
                userMemoryFacadeService
        );

        invokeWriteMemoryOperations(
                service,
                "agent-1",
                "message-1",
                List.of(
                        operation("create", "user", null, "", "preference", "代码示例偏好", "用户偏好代码问题使用 Java 示例回答。"),
                        operation("create", "agent", null, "core", "task", "面试复盘助手", "当前 Agent 主要用于帮助用户做后端面试复盘。")
                ),
                List.of(),
                List.of()
        );

        ArgumentCaptor<CreateUserMemoryRequest> userRequest = ArgumentCaptor.forClass(CreateUserMemoryRequest.class);
        verify(userMemoryFacadeService).createUserMemory(userRequest.capture());
        assertThat(userRequest.getValue().getTitle()).isEqualTo("代码示例偏好");
        assertThat(userRequest.getValue().getContent()).isEqualTo("用户偏好代码问题使用 Java 示例回答。");
        assertThat(userRequest.getValue().getSourceMessageId()).isEqualTo("message-1");

        ArgumentCaptor<CreateAgentMemoryRequest> agentRequest = ArgumentCaptor.forClass(CreateAgentMemoryRequest.class);
        verify(agentMemoryFacadeService).createAgentMemory(eq("agent-1"), agentRequest.capture());
        assertThat(agentRequest.getValue().getMemoryScope()).isEqualTo("core");
        assertThat(agentRequest.getValue().getTitle()).isEqualTo("面试复盘助手");
        assertThat(agentRequest.getValue().getContent()).isEqualTo("当前 Agent 主要用于帮助用户做后端面试复盘。");
    }

    @Test
    void shouldSkipExactDuplicateUserMemory() throws Exception {
        AgentMemoryMapper agentMemoryMapper = mock(AgentMemoryMapper.class);
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        AgentMemoryFacadeService agentMemoryFacadeService = mock(AgentMemoryFacadeService.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);

        when(userMemoryMapper.countExactDuplicate(
                "称呼偏好",
                "用户希望被称为少爷。"
        )).thenReturn(1);

        AutoAgentMemoryServiceImpl service = newService(
                agentMemoryMapper,
                userMemoryMapper,
                agentMemoryFacadeService,
                userMemoryFacadeService
        );

        invokeWriteMemoryOperations(
                service,
                "agent-1",
                "message-1",
                List.of(operation("create", "user", null, "", "preference", "称呼偏好", "用户希望被称为少爷。")),
                List.of(),
                List.of()
        );

        verifyNoInteractions(userMemoryFacadeService);
        verifyNoInteractions(agentMemoryFacadeService);
    }

    @Test
    void shouldUpdateExistingUserMemorySelectedByModel() throws Exception {
        AgentMemoryMapper agentMemoryMapper = mock(AgentMemoryMapper.class);
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        AgentMemoryFacadeService agentMemoryFacadeService = mock(AgentMemoryFacadeService.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);

        AutoAgentMemoryServiceImpl service = newService(
                agentMemoryMapper,
                userMemoryMapper,
                agentMemoryFacadeService,
                userMemoryFacadeService
        );

        invokeWriteMemoryOperations(
                service,
                "agent-1",
                "message-1",
                List.of(operation("update", "user", "user-memory-1", "", "fact", "用户姓名", "用户叫司马懿。")),
                List.of(UserMemory.builder()
                        .id("user-memory-1")
                        .title("用户姓名")
                        .content("用户叫乔国宇。")
                        .build()),
                List.of()
        );

        ArgumentCaptor<UpdateUserMemoryRequest> updateRequest = ArgumentCaptor.forClass(UpdateUserMemoryRequest.class);
        verify(userMemoryFacadeService).updateUserMemory(eq("user-memory-1"), updateRequest.capture());
        assertThat(updateRequest.getValue().getTitle()).isEqualTo("用户姓名");
        assertThat(updateRequest.getValue().getContent()).isEqualTo("用户叫司马懿。");
        verifyNoInteractions(agentMemoryFacadeService);
    }

    @Test
    void shouldIgnoreUpdateWithMemoryIdOutsideProvidedContext() throws Exception {
        AgentMemoryMapper agentMemoryMapper = mock(AgentMemoryMapper.class);
        UserMemoryMapper userMemoryMapper = mock(UserMemoryMapper.class);
        AgentMemoryFacadeService agentMemoryFacadeService = mock(AgentMemoryFacadeService.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);

        AutoAgentMemoryServiceImpl service = newService(
                agentMemoryMapper,
                userMemoryMapper,
                agentMemoryFacadeService,
                userMemoryFacadeService
        );

        invokeWriteMemoryOperations(
                service,
                "agent-1",
                "message-1",
                List.of(operation("update", "user", "invented-id", "", "fact", "用户姓名", "用户叫司马懿。")),
                List.of(UserMemory.builder()
                        .id("user-memory-1")
                        .title("用户姓名")
                        .content("用户叫乔国宇。")
                        .build()),
                List.of(AgentMemory.builder()
                        .id("agent-memory-1")
                        .agentId("agent-1")
                        .memoryScope("core")
                        .title("Agent 职责")
                        .content("当前 Agent 用于面试复盘。")
                        .build())
        );

        verifyNoInteractions(userMemoryFacadeService);
        verifyNoInteractions(agentMemoryFacadeService);
    }

    private AutoAgentMemoryServiceImpl newService(
            AgentMemoryMapper agentMemoryMapper,
            UserMemoryMapper userMemoryMapper,
            AgentMemoryFacadeService agentMemoryFacadeService,
            UserMemoryFacadeService userMemoryFacadeService
    ) {
        return new AutoAgentMemoryServiceImpl(
                mock(AgentMapper.class),
                mock(ChatMessageMapper.class),
                agentMemoryMapper,
                userMemoryMapper,
                mock(AgentMemoryJobStateMapper.class),
                agentMemoryFacadeService,
                userMemoryFacadeService,
                mock(ChatClientRegistry.class),
                new ObjectMapper()
        );
    }

    private void invokeWriteMemoryOperations(
            AutoAgentMemoryServiceImpl service,
            String agentId,
            String sourceMessageId,
            List<AutoAgentMemoryServiceImpl.MemoryOperation> operations,
            List<UserMemory> existingUserMemories,
            List<AgentMemory> existingAgentMemories
    ) throws Exception {
        Method method = AutoAgentMemoryServiceImpl.class.getDeclaredMethod(
                "writeMemoryOperations",
                String.class,
                String.class,
                List.class,
                List.class,
                List.class
        );
        method.setAccessible(true);
        method.invoke(service, agentId, sourceMessageId, operations, existingUserMemories, existingAgentMemories);
    }

    private AutoAgentMemoryServiceImpl.MemoryOperation operation(
            String action,
            String target,
            String existingMemoryId,
            String scope,
            String type,
            String title,
            String content
    ) {
        AutoAgentMemoryServiceImpl.MemoryOperation operation = new AutoAgentMemoryServiceImpl.MemoryOperation();
        operation.setAction(action);
        operation.setMemoryTarget(target);
        operation.setExistingMemoryId(existingMemoryId);
        operation.setMemoryScope(scope);
        operation.setMemoryType(type);
        operation.setTitle(title);
        operation.setContent(content);
        return operation;
    }
}
