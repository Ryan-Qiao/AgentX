package com.kama.jchatmind.agent;

import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    // 智能体 ID
    private String agentId;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 状态
    private AgentState agentState;

    // 可用的工具
    private List<ToolCallback> availableTools;

    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型的聊天会话 ID
    private String chatSessionId;

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    private String agentMemoryPrompt;

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    // AI 返回的，已经持久化，但是需要 sse 发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public JChatMind() {
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     Double temperature,
                     Double topP,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     String agentMemoryPrompt
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.agentMemoryPrompt = agentMemoryPrompt;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .temperature(temperature)
                .topP(topP)
                // 关闭 SpringAI 自带的内部工具自动执行，工具调用由 Agent Loop 接管。
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    private Set<String> availableToolNames() {
        if (availableTools == null || availableTools.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (ToolCallback toolCallback : availableTools) {
            names.add(toolCallback.getToolDefinition().name());
        }
        return names;
    }

    private List<AssistantMessage.ToolCall> executableToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> availableToolNames = availableToolNames();
        return toolCalls.stream()
                .filter(toolCall -> availableToolNames.contains(toolCall.name()))
                .filter(toolCall -> !"terminate".equals(toolCall.name()))
                .toList();
    }

    private AssistantMessage withoutToolCalls(AssistantMessage output) {
        return AssistantMessage.builder()
                .content(output.getText())
                .properties(output.getMetadata())
                .media(output.getMedia())
                .toolCalls(Collections.emptyList())
                .build();
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要 Agent 持久化的 Message 子类有以下两类
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发送问题之间就已经持久化过了
    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    // 刷新 pendingMessages, 将数据通过 sse 发送给前端
    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    private void sendAgentDone() {
        sseService.send(this.chatSessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_DONE)
                .payload(SseMessage.Payload.builder()
                        .done(true)
                        .build())
                .build());
    }

    private String buildUserFacingErrorMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        String detail = cause.getMessage();
        if (detail != null) {
            String normalized = detail.toLowerCase();
            if (normalized.contains("401")
                    || normalized.contains("authentication")
                    || normalized.contains("api key")
                    || normalized.contains("unauthorized")) {
                return "模型调用失败：API Key 无效或未配置，请检查当前 Agent 对应模型的环境变量后重启后端。";
            }
        }

        if (!StringUtils.hasText(detail)) {
            return "模型调用失败：后端 Agent 执行过程中发生未知错误，请查看服务日志。";
        }
        return "模型调用失败：" + detail;
    }

    private void notifyRunError(Exception e) {
        try {
            saveMessage(new AssistantMessage(buildUserFacingErrorMessage(e)));
            refreshPendingMessages();
        } catch (Exception notifyException) {
            log.warn("Failed to notify frontend about agent error", notifyException);
        } finally {
            sendAgentDone();
        }
    }

    // thinkPrompt 应该放到 system 中还是
    private boolean think() {
        // 构建决策提示词，根据是否有可用的知识库来调整
        String thinkPrompt;
        String toolUsePolicy = """

                【工具使用规则】
                - 只有当用户明确要求实时信息、当前城市、当前日期、天气、数据库查询或知识库检索时，才调用对应工具。
                - 用户要求写文章、写报告、写提纲、改写、总结、解释概念、普通闲聊时，直接回答，不要为了补充背景擅自调用 getCity、getDate 或 weather。
                - weather 只用于天气查询；不要在城市介绍、城市报告、旅游文案等普通写作任务中调用 weather。
                - getCity 只用于查询用户当前位置；不要把它当作文章主题城市，也不要用当前位置替代用户明确指定的城市。
                - getDate 只用于用户询问当前日期，或天气等确实需要日期参数的工具链。
                """;
        String memoryPrompt = StringUtils.hasLength(this.agentMemoryPrompt) ? this.agentMemoryPrompt : "";

        if (this.availableKbs != null && !this.availableKbs.isEmpty()) {
            thinkPrompt = """
                    现在你是一个智能的具体「决策模块」
                    请根据当前对话上下文，决定下一步的动作。
                    %s
                    %s

                    【结束规则】
                    - 如果当前上下文中的工具结果已经足够回答用户，请直接给用户最终回答，不要再调用工具。
                    - 最终回答必须面向用户总结工具返回的信息，不能只说明“已完成”。

                    【额外信息】
                    - 你目前拥有的知识库列表以及描述：%s
                    - 如果有缺失的上下文时，优先从知识库中进行搜索
                    - 调用 KnowledgeTool 时，kbsId 必须从上面的知识库列表中选择真实 UUID，禁止编造 default、默认知识库、unknown 等不存在的 ID
                    """.formatted(toolUsePolicy, memoryPrompt, this.availableKbs);
        } else {
            thinkPrompt = """
                    现在你是一个智能的具体「决策模块」
                    请根据当前对话上下文，决定下一步的动作。
                    %s
                    %s

                    【结束规则】
                    - 如果当前上下文中的工具结果已经足够回答用户，请直接给用户最终回答，不要再调用工具。
                    - 最终回答必须面向用户总结工具返回的信息，不能只说明“已完成”。
                    """.formatted(toolUsePolicy, memoryPrompt);
        }

        // 将 thinkPrompt 通过 .user(thinkPrompt) 的方式构造进入 chatClient 中
        // 既能让每次 messageList 的最后一条是 本条提示词，
        // 又能够避免将 thinkPrompt 加入到聊天记录中
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        // 直接获取完整响应，避免流式分片导致最后保存的内容为空。
        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse
                .getResult()
                .getOutput();

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        List<AssistantMessage.ToolCall> executableToolCalls = executableToolCalls(toolCalls);
        if (toolCalls != null && !toolCalls.isEmpty() && executableToolCalls.size() != toolCalls.size()) {
            log.warn("Ignore non-executable tool calls: requested={}, executable={}",
                    toolCalls.stream().map(AssistantMessage.ToolCall::name).toList(),
                    executableToolCalls.stream().map(AssistantMessage.ToolCall::name).toList());
        }

        // 保存
        saveMessage(executableToolCalls.isEmpty() ? withoutToolCalls(output) : output);
        refreshPendingMessages();

        // 打印工具调用
        logToolCalls(toolCalls);

        // 如果工具调用不为空，则进入执行阶段
        return !executableToolCalls.isEmpty() && executableToolCalls.size() == toolCalls.size();
    }


    // 执行
    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果：{}", collect);

        // 保存工具调用
        saveMessage(toolResponseMessage);
        refreshPendingMessages();

    }

    // 单个步骤模板
    private void step() {
        if (think()) {
            execute();
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                // 当前步骤，用于实现 Agent Loop
                int currentStep = i + 1;
                step();
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            notifyRunError(e);
        }
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
