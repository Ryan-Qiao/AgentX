package com.kama.jchatmind.agent;

import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.trace.AgentTraceContext;
import com.kama.jchatmind.trace.AgentTraceRecorder;
import com.kama.jchatmind.trace.TraceEventStatus;
import com.kama.jchatmind.trace.TraceEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.Generation;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Instant;
import java.util.LinkedHashMap;

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

    private static final Integer EMPTY_RESPONSE_MAX_RETRIES = 1;

    private static final String EMPTY_RESPONSE_FALLBACK = "模型本次返回了空内容，请再试一次。";

    private static final Pattern DSML_TOOL_CALLS_PATTERN = Pattern.compile(
            "(?s)<[｜|]\\s*[｜|]DSML[｜|]\\s*[｜|]tool_calls>.*?</[｜|]\\s*[｜|]DSML[｜|]\\s*[｜|]tool_calls>");

    private static final Pattern DSML_INVOKE_PATTERN = Pattern.compile(
            "(?s)<[｜|]\\s*[｜|]DSML[｜|]\\s*[｜|]invoke\\s+name=\"([^\"]+)\">(.*?)</[｜|]\\s*[｜|]DSML[｜|]\\s*[｜|]invoke>");

    private static final Pattern DSML_PARAMETER_PATTERN = Pattern.compile(
            "(?s)<[｜|]\\s*[｜|]DSML[｜|]\\s*[｜|]parameter\\s+name=\"([^\"]+)\">(.*?)</[｜|]\\s*[｜|]DSML[｜|]\\s*[｜|]parameter>");

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

    private String modelName;
    private AgentTraceContext traceContext;
    private AgentTraceRecorder traceRecorder;
    private int currentStep;
    private Instant runStartedAt;
    private String lastAssistantMessageId;

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
                     String agentMemoryPrompt,
                     String modelName,
                     AgentTraceContext traceContext,
                     AgentTraceRecorder traceRecorder
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
        this.modelName = modelName;
        this.traceContext = traceContext;
        this.traceRecorder = traceRecorder;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

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

    private AssistantMessage emptyResponseFallback() {
        return AssistantMessage.builder()
                .content(EMPTY_RESPONSE_FALLBACK)
                .toolCalls(Collections.emptyList())
                .build();
    }

    private String stripDsmlToolCalls(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return DSML_TOOL_CALLS_PATTERN.matcher(text).replaceAll("").trim();
    }

    private List<AssistantMessage.ToolCall> parseDsmlToolCalls(String text) {
        if (!StringUtils.hasText(text)) {
            return Collections.emptyList();
        }

        Matcher toolCallsMatcher = DSML_TOOL_CALLS_PATTERN.matcher(text);
        if (!toolCallsMatcher.find()) {
            return Collections.emptyList();
        }

        List<AssistantMessage.ToolCall> parsedToolCalls = new ArrayList<>();
        toolCallsMatcher.reset();
        while (toolCallsMatcher.find()) {
            String toolCallsBlock = toolCallsMatcher.group();
            Matcher invokeMatcher = DSML_INVOKE_PATTERN.matcher(toolCallsBlock);
            while (invokeMatcher.find()) {
                String toolName = invokeMatcher.group(1);
                String invokeBody = invokeMatcher.group(2);
                parsedToolCalls.add(new AssistantMessage.ToolCall(
                        "dsml_call_" + UUID.randomUUID().toString().replace("-", ""),
                        "function",
                        toolName,
                        parseDsmlArguments(invokeBody)
                ));
            }
        }
        return parsedToolCalls;
    }

    private String parseDsmlArguments(String invokeBody) {
        if (!StringUtils.hasText(invokeBody)) {
            return "{}";
        }

        Matcher parameterMatcher = DSML_PARAMETER_PATTERN.matcher(invokeBody);
        Map<String, String> arguments = parameterMatcher.results()
                .collect(Collectors.toMap(
                        match -> match.group(1),
                        match -> match.group(2).trim(),
                        (first, second) -> second
                ));

        if (arguments.isEmpty()) {
            return "{}";
        }

        return arguments.entrySet().stream()
                .map(entry -> jsonQuote(entry.getKey()) + ":" + jsonQuote(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String jsonQuote(String value) {
        String text = value == null ? "" : value;
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private AssistantMessage normalizeDsmlToolCalls(AssistantMessage output) {
        if (output.hasToolCalls()) {
            return output;
        }

        List<AssistantMessage.ToolCall> parsedToolCalls = parseDsmlToolCalls(output.getText());
        if (parsedToolCalls.isEmpty()) {
            return output;
        }

        String cleanedContent = stripDsmlToolCalls(output.getText());
        log.warn("Parsed DSML tool calls from assistant text: agentId={}, chatSessionId={}, tools={}",
                this.agentId,
                this.chatSessionId,
                parsedToolCalls.stream().map(AssistantMessage.ToolCall::name).toList());

        return AssistantMessage.builder()
                .content(cleanedContent)
                .properties(output.getMetadata())
                .media(output.getMedia())
                .toolCalls(parsedToolCalls)
                .build();
    }

    private void replaceLastChatResponseOutput(AssistantMessage output) {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");
        this.lastChatResponse = new ChatResponse(
                List.of(new Generation(output, this.lastChatResponse.getResult().getMetadata())),
                this.lastChatResponse.getMetadata()
        );
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
                            .traceId(this.traceContext.traceId())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            lastAssistantMessageId = chatMessage.getChatMessageId();
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .traceId(this.traceContext.traceId())
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

    private void sendAgentStatus(SseMessage.Type type, String statusText) {
        sseService.send(this.chatSessionId, SseMessage.builder()
                .type(type)
                .payload(SseMessage.Payload.builder()
                        .statusText(statusText)
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

    private String renderAgentIdentityPrompt() {
        String agentName = StringUtils.hasText(this.name) ? this.name : "未命名 Agent";
        return """

                【当前 Agent 身份】
                名称：%s
                - 当前 Agent 名称是你在本系统中的基础身份。
                - 当用户询问你是谁、你的名字或当前身份时，应以该名称回答。
                """.formatted(agentName);
    }

    private String renderAgentSystemInstructionPrompt() {
        if (StringUtils.hasText(this.systemPrompt)) {
            return """

                    【当前 Agent 系统指令】
                    %s
                    """.formatted(this.systemPrompt);
        }
        return """

                【当前 Agent 系统指令】
                当前 Agent 未配置额外系统指令。请以当前 Agent 身份为基础，根据用户问题和可用上下文提供帮助。
                """;
    }

    private String renderContextPriorityPrompt() {
        return """

                【上下文优先级】
                1. 当前 Agent 身份和系统指令：决定你是谁、行为约束、语气、任务边界和输出要求。
                2. 当前用户消息和当前会话历史：决定本轮要回答什么。
                3. 当前 Agent 长期记忆：只服务于当前 Agent，不能覆盖当前 Agent 身份和系统指令。
                4. 用户长期记忆：只描述用户，不能定义或修改当前 Agent 的身份和系统指令。
                """;
    }

    private String renderToolPolicyPrompt() {
        return """

                【工具使用原则】
                - 只有当工具能显著提升回答准确性，或用户明确要求实时信息、外部信息、数据库查询、知识库检索等能力时，才调用工具。
                - 不要为了普通闲聊、写作、解释概念、总结改写等任务调用无关工具。
                - 不要调用【当前可用工具】目录之外的工具。
                - 工具结果已经足够回答用户时，应停止继续调用工具并给出最终回答。
                """;
    }

    private String renderToolCatalogPrompt() {
        if (this.availableTools == null || this.availableTools.isEmpty()) {
            return """

                    【当前可用工具】
                    当前没有可调用工具。
                    """;
        }

        String toolItems = this.availableTools.stream()
                .map(ToolCallback::getToolDefinition)
                .collect(Collectors.toMap(
                        definition -> definition.name(),
                        definition -> StringUtils.hasText(definition.description())
                                ? definition.description()
                                : "未提供工具描述。",
                        (first, second) -> first
                ))
                .entrySet()
                .stream()
                .map(entry -> "- %s：%s".formatted(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining("\n"));

        return """

                【当前可用工具】
                %s
                """.formatted(toolItems);
    }

    private String renderKnowledgeBasePrompt() {
        if (this.availableKbs == null || this.availableKbs.isEmpty()) {
            return "";
        }
        return """

                【当前可用知识库】
                %s

                【知识库使用规则】
                - 如果回答需要当前知识库中的信息，优先调用 KnowledgeTool 检索。
                - 调用 KnowledgeTool 时，kbsId 必须从当前可用知识库列表中选择真实 UUID。
                - 禁止编造 default、默认知识库、unknown 等不存在的知识库 ID。
                """.formatted(this.availableKbs);
    }

    private String renderEndingRulePrompt() {
        return """

                【结束规则】
                - 如果当前上下文中的工具结果已经足够回答用户，请直接给用户最终回答，不要再调用工具。
                - 最终回答必须面向用户总结工具返回的信息，不能只说明“已完成”。
                """;
    }

    private String buildSystemPromptText() {
        String memoryPrompt = StringUtils.hasText(this.agentMemoryPrompt) ? this.agentMemoryPrompt : "";
        return """
                请根据当前对话上下文决定下一步动作，并保持当前 Agent 的身份、系统指令和任务边界。
                %s
                %s
                %s
                %s
                %s
                %s
                %s
                %s
                """.formatted(
                renderAgentIdentityPrompt(),
                renderAgentSystemInstructionPrompt(),
                renderContextPriorityPrompt(),
                memoryPrompt,
                renderToolPolicyPrompt(),
                renderToolCatalogPrompt(),
                renderKnowledgeBasePrompt(),
                renderEndingRulePrompt()
        );
    }

    private void trace(TraceEventType type,
                       TraceEventStatus status,
                       Instant startedAt,
                       String name,
                       Map<String, Object> payload,
                       Throwable error) {
        if (traceRecorder == null || traceContext == null) return;
        traceRecorder.record(traceContext, type, status,
                currentStep == 0 ? null : currentStep, name, startedAt, payload, error);
    }

    private Map<String, Object> payload(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i + 1] != null) result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }

    private boolean think() {
        String systemPromptText = buildSystemPromptText();

        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        AssistantMessage output = null;
        List<AssistantMessage.ToolCall> toolCalls = Collections.emptyList();
        List<AssistantMessage.ToolCall> executableToolCalls = Collections.emptyList();

        for (int attempt = 0; attempt <= EMPTY_RESPONSE_MAX_RETRIES; attempt++) {
            sendAgentStatus(SseMessage.Type.AI_THINKING,
                    attempt == 0 ? "正在理解你的问题" : "模型返回为空，正在重试");

            Instant modelCallStartedAt = Instant.now();
            trace(TraceEventType.MODEL_CALL_STARTED, TraceEventStatus.STARTED, modelCallStartedAt,
                    this.modelName, payload(
                            "attemptNo", attempt + 1,
                            "messageCount", this.chatMemory.get(this.chatSessionId).size(),
                            "toolNames", availableToolNames()
                    ), null);
            try {
                // 直接获取完整响应，避免流式分片导致最后保存的内容为空。
                this.lastChatResponse = this.chatClient
                        .prompt(prompt)
                        .system(systemPromptText)
                        .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                        .call()
                        .chatClientResponse()
                        .chatResponse();
            } catch (Exception e) {
                trace(TraceEventType.MODEL_CALL_FAILED, TraceEventStatus.FAILED, modelCallStartedAt,
                        this.modelName, payload("attemptNo", attempt + 1), e);
                throw e;
            }

            Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

            output = this.lastChatResponse
                    .getResult()
                    .getOutput();
            output = normalizeDsmlToolCalls(output);
            replaceLastChatResponseOutput(output);

            toolCalls = output.getToolCalls();
            executableToolCalls = executableToolCalls(toolCalls);
            trace(TraceEventType.MODEL_CALL_COMPLETED, TraceEventStatus.COMPLETED, modelCallStartedAt,
                    this.modelName, payload(
                            "attemptNo", attempt + 1,
                            "content", output.getText(),
                            "toolCalls", toolCalls
                    ), null);

            if (StringUtils.hasText(output.getText()) || !executableToolCalls.isEmpty()) {
                break;
            }

            log.warn("Model returned empty assistant content, retrying if possible: agentId={}, chatSessionId={}, attempt={}/{}",
                    this.agentId, this.chatSessionId, attempt + 1, EMPTY_RESPONSE_MAX_RETRIES + 1);
            trace(TraceEventType.MODEL_EMPTY_RESPONSE_RETRY, TraceEventStatus.COMPLETED, null,
                    this.modelName, payload("attemptNo", attempt + 1, "maxAttempts", EMPTY_RESPONSE_MAX_RETRIES + 1), null);
        }

        Assert.notNull(output, "Assistant output cannot be null");
        if (toolCalls != null && !toolCalls.isEmpty() && executableToolCalls.size() != toolCalls.size()) {
            log.warn("Ignore non-executable tool calls: requested={}, executable={}",
                    toolCalls.stream().map(AssistantMessage.ToolCall::name).toList(),
                    executableToolCalls.stream().map(AssistantMessage.ToolCall::name).toList());
        }

        // 保存
        AssistantMessage messageToSave = executableToolCalls.isEmpty() ? withoutToolCalls(output) : output;
        if (executableToolCalls.isEmpty() && !StringUtils.hasText(messageToSave.getText())) {
            log.warn("Model response is still empty after retry, saving fallback message: agentId={}, chatSessionId={}",
                    this.agentId, this.chatSessionId);
            messageToSave = emptyResponseFallback();
        }
        saveMessage(messageToSave);
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

        List<AssistantMessage.ToolCall> calls = executableToolCalls(this.lastChatResponse
                .getResult()
                .getOutput()
                .getToolCalls());
        List<String> toolNames = calls.stream()
                .map(AssistantMessage.ToolCall::name)
                .toList();
        sendAgentStatus(SseMessage.Type.AI_EXECUTING,
                toolNames.isEmpty() ? "正在调用工具" : "正在调用工具：" + String.join("、", toolNames));

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        Map<String, Instant> toolStartedAt = new LinkedHashMap<>();
        for (AssistantMessage.ToolCall call : calls) {
            Instant startedAt = Instant.now();
            toolStartedAt.put(call.id(), startedAt);
            trace(TraceEventType.TOOL_CALL_STARTED, TraceEventStatus.STARTED, startedAt,
                    call.name(), payload("toolCallId", call.id(), "arguments", call.arguments()), null);
        }

        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);
        } catch (Exception e) {
            for (AssistantMessage.ToolCall call : calls) {
                trace(TraceEventType.TOOL_CALL_FAILED, TraceEventStatus.FAILED, toolStartedAt.get(call.id()),
                        call.name(), payload("toolCallId", call.id()), e);
            }
            throw e;
        }

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
            trace(TraceEventType.TOOL_CALL_COMPLETED, TraceEventStatus.COMPLETED,
                    toolStartedAt.get(response.id()), response.name(),
                    payload("toolCallId", response.id(), "result", response.responseData()), null);
        }

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
        Instant stepStartedAt = Instant.now();
        trace(TraceEventType.STEP_STARTED, TraceEventStatus.STARTED, stepStartedAt,
                "step-" + currentStep, payload("stepNo", currentStep), null);
        if (think()) {
            execute();
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
        trace(TraceEventType.STEP_COMPLETED, TraceEventStatus.COMPLETED, stepStartedAt,
                "step-" + currentStep, payload("stepNo", currentStep, "agentState", agentState.name()), null);
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            runStartedAt = Instant.now();
            trace(TraceEventType.RUN_STARTED, TraceEventStatus.STARTED, runStartedAt, this.name,
                    payload(
                            "agentName", this.name,
                            "model", this.modelName,
                            "maxSteps", MAX_STEPS,
                            "availableTools", availableToolNames(),
                            "knowledgeBaseIds", this.availableKbs == null ? List.of() : this.availableKbs.stream().map(KnowledgeBaseDTO::getId).toList()
                    ), null);
            trace(TraceEventType.CONTEXT_BUILT, TraceEventStatus.COMPLETED, null, "context",
                    payload("historyMessageCount", this.chatMemory.get(this.chatSessionId).size()), null);
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                // 当前步骤，用于实现 Agent Loop
                currentStep = i + 1;
                step();
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;
            trace(TraceEventType.RUN_COMPLETED, TraceEventStatus.COMPLETED, runStartedAt, this.name,
                    payload(
                            "finishReason", currentStep >= MAX_STEPS ? "MAX_STEPS_REACHED" : "FINAL_RESPONSE",
                            "assistantMessageId", lastAssistantMessageId
                    ), null);
            sendAgentDone();
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            trace(TraceEventType.RUN_FAILED, TraceEventStatus.FAILED, runStartedAt, this.name,
                    payload("finishReason", "INTERNAL_ERROR", "assistantMessageId", lastAssistantMessageId), e);
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
