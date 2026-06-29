package com.kama.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.AgentMemoryFacadeService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
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

@Component
public class JChatMindFactory {

    private static final Logger log = LoggerFactory.getLogger(JChatMindFactory.class);
    private static final int MAX_AGENT_CORE_MEMORIES = 10;
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

    // 运行时 Agent 配置
    private AgentDTO agentConfig;

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
            AgentMemoryFacadeService agentMemoryFacadeService
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
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
     * 将数据库中存储的记忆恢复成 List<Message> 结构
     */
    private List<Message> loadMemory(String chatSessionId) {
        int messageLength = Math.max(2, agentConfig.getChatOptions().getMessageLength());
        List<ChatMessageDTO> chatMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength);
        List<Message> memory = new ArrayList<>();
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    break;
                case USER:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(new UserMessage(chatMessageDTO.getContent()));
                    break;
                case ASSISTANT:
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(chatMessageDTO.getMetadata()
                                    .getToolCalls())
                            .build());
                    break;
                case TOOL:
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(chatMessageDTO
                                    .getMetadata()
                                    .getToolResponse()))
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
            agentConfig = agentConverter.toDTO(agent);
            return agentConfig;
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
        String latestUserMessage = latestUserMessage(memory);
        List<Tool> runtimeTools = toolFacadeService.getFixedTools()
                .stream()
                .filter(tool -> hasKnowledgeBases || !"KnowledgeTool".equals(tool.getName()))
                .filter(tool -> shouldExposeFixedTool(tool, latestUserMessage))
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

    private boolean shouldExposeFixedTool(Tool tool, String latestUserMessage) {
        return switch (tool.getName()) {
            case "cityTool" -> asksCurrentLocation(latestUserMessage) || asksWeather(latestUserMessage);
            case "dateTool" -> asksCurrentDate(latestUserMessage) || asksWeather(latestUserMessage);
            case "weatherTool" -> asksWeather(latestUserMessage);
            default -> true;
        };
    }

    private String latestUserMessage(List<Message> memory) {
        for (int i = memory.size() - 1; i >= 0; i--) {
            Message message = memory.get(i);
            if (message instanceof UserMessage userMessage) {
                return userMessage.getText();
            }
        }
        return "";
    }

    private boolean asksWeather(String message) {
        if (!StringUtils.hasLength(message)) {
            return false;
        }
        return message.contains("天气")
                || message.contains("气温")
                || message.contains("温度")
                || message.contains("降水")
                || message.contains("下雨")
                || message.contains("雨")
                || message.contains("出行");
    }

    private boolean asksCurrentDate(String message) {
        if (!StringUtils.hasLength(message)) {
            return false;
        }
        return message.contains("今天几号")
                || message.contains("今天日期")
                || message.contains("当前日期")
                || message.contains("现在日期")
                || message.contains("今天是几号");
    }

    private boolean asksCurrentLocation(String message) {
        if (!StringUtils.hasLength(message)) {
            return false;
        }
        return message.contains("当前城市")
                || message.contains("当前位置")
                || message.contains("我在哪")
                || message.contains("所在城市");
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

    private String renderAgentMemoryPrompt(String agentId) {
        List<AgentMemoryDTO> memories = agentMemoryFacadeService.getEnabledAgentMemories(
                agentId,
                MAX_AGENT_CORE_MEMORIES
        );
        if (memories.isEmpty()) {
            return "";
        }

        String memoryItems = memories.stream()
                .map(memory -> "- [%s] %s：%s".formatted(
                        StringUtils.hasText(memory.getMemoryType()) ? memory.getMemoryType() : "fact",
                        memory.getTitle(),
                        memory.getContent()
                ))
                .collect(Collectors.joining("\n"));

        return """

                【当前 Agent 长期记忆】
                以下记忆只适用于当前 Agent，是跨会话保存的长期上下文。使用规则：
                - 可以参考这些记忆保持当前 Agent 的连续性。
                - 如果记忆与用户最新输入冲突，以用户最新输入为准。
                - 不要主动暴露“内部记忆”这个机制，除非用户询问。
                %s
                """.formatted(memoryItems);
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
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId,
            String agentMemoryPrompt
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
                agentMemoryPrompt
        );
    }

    /**
     * 创建一个 JChatMind 实例
     */
    public JChatMind create(String agentId, String chatSessionId) {
        Agent agent = loadAgent(agentId);
        AgentDTO agentConfig = toAgentConfig(agent);
        List<Message> memory = loadMemory(chatSessionId);

        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig, knowledgeBases, memory);
        // 将工具调用转换成 ToolCallback 的形式
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);
        String agentMemoryPrompt = renderAgentMemoryPrompt(agent.getId());

        return buildAgentRuntime(
                agent,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId,
                agentMemoryPrompt
        );
    }
}
