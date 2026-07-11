package com.kama.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.AgentMemoryDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.UserMemoryDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.AgentMemoryFacadeService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import com.kama.jchatmind.trace.AgentTraceContext;
import com.kama.jchatmind.trace.AgentTraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Component
public class JChatMindFactory {

    private static final Logger log = LoggerFactory.getLogger(JChatMindFactory.class);
    private static final int MAX_AGENT_CORE_MEMORIES = 10;
    private static final int MAX_AGENT_RETRIEVED_MEMORIES = 5;
    private static final int MAX_USER_MEMORIES = 10;
    private static final Pattern DSML_TOOL_CALLS_PATTERN = Pattern.compile(
            "(?s)<[｜|]\\s*[｜|]DSML[｜|]\\s*[｜|]tool_calls>.*?</[｜|]\\s*[｜|]DSML[｜|]\\s*[｜|]tool_calls>");
    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final AgentMemoryFacadeService agentMemoryFacadeService;
    private final UserMemoryFacadeService userMemoryFacadeService;
    private final AgentTraceRecorder agentTraceRecorder;

    public JChatMindFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            AgentMemoryFacadeService agentMemoryFacadeService,
            UserMemoryFacadeService userMemoryFacadeService,
            AgentTraceRecorder agentTraceRecorder
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.agentMemoryFacadeService = agentMemoryFacadeService;
        this.userMemoryFacadeService = userMemoryFacadeService;
        this.agentTraceRecorder = agentTraceRecorder;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    private String stripDsmlToolCalls(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return DSML_TOOL_CALLS_PATTERN.matcher(text).replaceAll("").trim();
    }

    /**
     * 将数据库中存储的记忆恢复成 List<Message> 结构
     */
    private List<Message> loadMemory(String chatSessionId, AgentDTO agentConfig) {
        int messageLength = Math.max(2, agentConfig.getChatOptions().getMessageLength());
        List<ChatMessageDTO> chatMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength);
        List<Message> memory = new ArrayList<>();
        Set<String> pendingToolCallIds = new HashSet<>();
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    pendingToolCallIds.clear();
                    break;
                case USER:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(new UserMessage(chatMessageDTO.getContent()));
                    pendingToolCallIds.clear();
                    break;
                case ASSISTANT:
                    String assistantContent = stripDsmlToolCalls(chatMessageDTO.getContent());
                    List<AssistantMessage.ToolCall> toolCalls = chatMessageDTO.getMetadata() == null
                            ? Collections.emptyList()
                            : chatMessageDTO.getMetadata().getToolCalls();
                    if (!StringUtils.hasText(assistantContent) && (toolCalls == null || toolCalls.isEmpty())) {
                        continue;
                    }
                    memory.add(AssistantMessage.builder()
                            .content(assistantContent)
                            .toolCalls(toolCalls)
                            .build());
                    pendingToolCallIds.clear();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        pendingToolCallIds.addAll(toolCalls.stream()
                                .map(AssistantMessage.ToolCall::id)
                                .collect(Collectors.toSet()));
                    }
                    break;
                case TOOL:
                    if (chatMessageDTO.getMetadata() == null
                            || chatMessageDTO.getMetadata().getToolResponse() == null) {
                        continue;
                    }
                    ToolResponseMessage.ToolResponse toolResponse = chatMessageDTO
                            .getMetadata()
                            .getToolResponse();
                    if (!pendingToolCallIds.remove(toolResponse.id())) {
                        log.warn("Skip orphan tool response in memory: sessionId={}, messageId={}, toolCallId={}",
                                chatSessionId, chatMessageDTO.getId(), toolResponse.id());
                        continue;
                    }
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(toolResponse))
                            .build());
                    break;
                default:
                    log.error("不支持的 Message 类型: {}, content = {}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent()
                    );
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
        return memory;
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            return agentConverter.toDTO(agent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    private List<Tool> resolveRuntimeTools(
            AgentDTO agentConfig,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<Message> memory
    ) {
        // 固定工具（系统强制）
        boolean hasKnowledgeBases = knowledgeBases != null && !knowledgeBases.isEmpty();
        List<Tool> runtimeTools = toolFacadeService.getFixedTools()
                .stream()
                .filter(tool -> hasKnowledgeBases || !"KnowledgeTool".equals(tool.getName()))
                .map(tool -> scopeKnowledgeTool(tool, knowledgeBases))
                .collect(Collectors.toCollection(ArrayList::new));

        // 可选工具（按 Agent 配置）
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    private Tool scopeKnowledgeTool(Tool tool, List<KnowledgeBaseDTO> knowledgeBases) {
        if (tool instanceof KnowledgeTools knowledgeTools) {
            return knowledgeTools.scopedTo(knowledgeBases);
        }
        return tool;
    }

    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

    private Object resolveToolTarget(Tool tool) {
        try {
            return AopUtils.isAopProxy(tool)
                    ? AopUtils.getTargetClass(tool)
                    : tool;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    private String latestUserText(List<Message> memory) {
        if (memory == null || memory.isEmpty()) {
            return "";
        }
        for (int i = memory.size() - 1; i >= 0; i--) {
            Message message = memory.get(i);
            if (message instanceof UserMessage && StringUtils.hasText(message.getText())) {
                return message.getText();
            }
        }
        return "";
    }

    private String renderMemoryPrompt(String agentId, String latestUserText) {
        List<UserMemoryDTO> userMemories = userMemoryFacadeService.getEnabledGlobalUserMemories(MAX_USER_MEMORIES);
        List<AgentMemoryDTO> agentCoreMemories = agentMemoryFacadeService.getEnabledAgentMemories(
                agentId,
                MAX_AGENT_CORE_MEMORIES
        );
        List<AgentMemoryDTO> agentRetrievedMemories = agentMemoryFacadeService.getRetrievedAgentMemories(
                agentId,
                latestUserText,
                MAX_AGENT_RETRIEVED_MEMORIES
        );
        if (userMemories.isEmpty() && agentCoreMemories.isEmpty() && agentRetrievedMemories.isEmpty()) {
            return "";
        }

        String userMemoryPrompt = "";
        if (!userMemories.isEmpty()) {
            String userMemoryItems = userMemories.stream()
                    .map(memory -> "- [%s] %s：%s".formatted(
                            StringUtils.hasText(memory.getMemoryType()) ? memory.getMemoryType() : "preference",
                            memory.getTitle(),
                            memory.getContent()
                    ))
                    .collect(Collectors.joining("\n"));
            userMemoryPrompt = """

                    【用户长期记忆】
                    以下内容是关于“用户”的画像、偏好、背景或事实，不是关于“当前 Agent”的设定。使用规则：
                    - 这些记忆只能用于更好地理解用户，不能定义、修改或暗示你的身份、角色、职业、能力范围或系统指令。
                    - 你的身份只能来自【当前 Agent 身份】和【当前 Agent 系统指令】。
                    - 对寒暄、身份询问、自我介绍、能力介绍等场景，不要主动提起、复述、追问或展开用户长期记忆。
                    - 只有当用户当前问题明确涉及某条记忆的主题时，才可以自然使用该记忆补充回答。
                    - 如果用户最新输入与这些记忆冲突，以用户最新输入为准。
                    - 不要主动暴露“用户记忆”这个机制，除非用户询问。
                    %s
                    """.formatted(userMemoryItems);
        }

        String agentCoreMemoryPrompt = "";
        if (!agentCoreMemories.isEmpty()) {
            String agentCoreMemoryItems = agentCoreMemories.stream()
                    .map(memory -> "- [%s] %s：%s".formatted(
                            StringUtils.hasText(memory.getMemoryType()) ? memory.getMemoryType() : "fact",
                            memory.getTitle(),
                            memory.getContent()
                    ))
                    .collect(Collectors.joining("\n"));
            agentCoreMemoryPrompt = """

                    【当前 Agent 核心记忆】
                    以下记忆只适用于当前 Agent，是跨会话保存的核心长期上下文。使用规则：
                    - 可以参考这些记忆保持当前 Agent 的身份、任务边界和长期连续性。
                    - 如果记忆与用户最新输入冲突，以用户最新输入为准。
                    - 不要主动暴露“内部记忆”这个机制，除非用户询问。
                    %s
                    """.formatted(agentCoreMemoryItems);
        }

        String agentRetrievedMemoryPrompt = "";
        if (!agentRetrievedMemories.isEmpty()) {
            String agentRetrievedMemoryItems = agentRetrievedMemories.stream()
                    .map(memory -> "- [%s] %s：%s".formatted(
                            StringUtils.hasText(memory.getMemoryType()) ? memory.getMemoryType() : "fact",
                            memory.getTitle(),
                            memory.getContent()
                    ))
                    .collect(Collectors.joining("\n"));
            agentRetrievedMemoryPrompt = """

                    【当前问题相关 Agent 记忆】
                    以下记忆是根据用户最新问题语义召回的，只适用于当前 Agent。使用规则：
                    - 只有当它们与当前问题相关时才使用。
                    - 如果召回记忆与当前会话上下文冲突，以当前会话上下文为准。
                    - 不要主动暴露“语义召回记忆”这个机制，除非用户询问。
                    %s
                    """.formatted(agentRetrievedMemoryItems);
        }

        return userMemoryPrompt + agentCoreMemoryPrompt + agentRetrievedMemoryPrompt;
    }

    private Double normalizeTemperature(String model, Double temperature) {
        if (temperature == null) {
            return null;
        }
        double maxTemperature = "glm-4.6".equals(model) ? 1.0 : 2.0;
        return Math.max(0.0, Math.min(maxTemperature, temperature));
    }

    private Double normalizeTopP(Double topP) {
        if (topP == null) {
            return null;
        }
        return Math.max(0.0, Math.min(1.0, topP));
    }

    private JChatMind buildAgentRuntime(
            Agent agent,
            AgentDTO agentConfig,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId,
            String memoryPrompt,
            AgentTraceContext traceContext
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        return new JChatMind(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                agentConfig.getChatOptions().getMessageLength(),
                normalizeTemperature(agent.getModel(), agentConfig.getChatOptions().getTemperature()),
                normalizeTopP(agentConfig.getChatOptions().getTopP()),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                memoryPrompt,
                agent.getModel(),
                traceContext,
                agentTraceRecorder
        );
    }

    /**
     * 创建一个 JChatMind 实例
     */
    public JChatMind create(String agentId, String chatSessionId, String traceId, String userMessageId) {
        Agent agent = loadAgent(agentId);
        AgentDTO agentConfig = toAgentConfig(agent);
        List<Message> memory = loadMemory(chatSessionId, agentConfig);

        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig, knowledgeBases, memory);
        // 将工具调用转换成 ToolCallback 的形式
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);
        String memoryPrompt = renderMemoryPrompt(agent.getId(), latestUserText(memory));

        return buildAgentRuntime(
                agent,
                agentConfig,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId,
                memoryPrompt,
                new AgentTraceContext(traceId, agentId, chatSessionId, userMessageId)
        );
    }
}
