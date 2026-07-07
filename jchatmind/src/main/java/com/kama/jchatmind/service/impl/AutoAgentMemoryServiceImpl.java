package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.AgentMemoryJobStateMapper;
import com.kama.jchatmind.mapper.AgentMemoryMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.AgentMemoryJobState;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.request.CreateAgentMemoryRequest;
import com.kama.jchatmind.service.AgentMemoryFacadeService;
import com.kama.jchatmind.service.AutoAgentMemoryService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
@AllArgsConstructor
public class AutoAgentMemoryServiceImpl implements AutoAgentMemoryService {
    private static final int DEFAULT_AUTO_MEMORY_INTERVAL = 10;
    private static final int MIN_AUTO_MEMORY_INTERVAL = 3;
    private static final int MAX_AUTO_MEMORY_INTERVAL = 50;
    private static final int MAX_RETURNED_MEMORIES = 5;
    private static final Set<String> VALID_MEMORY_SCOPES = Set.of("core", "retrieved");
    private static final Set<String> VALID_MEMORY_TYPES = Set.of("fact", "preference", "decision", "issue", "task", "feedback");
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|passwd|密码|密钥|身份证|银行卡)"
    );

    private final AgentMapper agentMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentMemoryMapper agentMemoryMapper;
    private final AgentMemoryJobStateMapper jobStateMapper;
    private final AgentMemoryFacadeService agentMemoryFacadeService;
    private final ChatClientRegistry chatClientRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public void consolidate(String agentId, String sessionId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || !Boolean.TRUE.equals(agent.getAutoMemoryEnabled())) {
            return;
        }

        int interval = normalizeInterval(agent.getAutoMemoryInterval());
        AgentMemoryJobState state = getOrCreateState(agentId, sessionId);
        int processedCount = state.getProcessedUserMessageCount() == null
                ? 0
                : state.getProcessedUserMessageCount();

        int totalUserMessages = chatMessageMapper.countUserMessages(sessionId);
        if (totalUserMessages - processedCount < interval) {
            return;
        }

        List<ChatMessage> userBatch = chatMessageMapper.selectUnprocessedUserMessages(
                sessionId,
                processedCount,
                interval
        );
        if (userBatch.size() < interval) {
            return;
        }

        ChatMessage firstUserMessage = userBatch.get(0);
        ChatMessage lastUserMessage = userBatch.get(userBatch.size() - 1);
        List<ChatMessage> conversation = loadConversationBatch(sessionId, firstUserMessage, lastUserMessage, interval);
        String promptText = renderConversation(conversation);
        if (!StringUtils.hasText(promptText)) {
            updateJobState(agentId, sessionId, processedCount + interval, lastUserMessage);
            return;
        }

        List<ExtractedMemory> memories = extractMemories(agent, promptText);
        writeMemories(agentId, lastUserMessage.getId(), memories);
        updateJobState(agentId, sessionId, processedCount + interval, lastUserMessage);
        log.info("自动记忆整理完成: agentId={}, sessionId={}, userMessages={}, extracted={}",
                agentId,
                sessionId,
                interval,
                memories.size());
    }

    private AgentMemoryJobState getOrCreateState(String agentId, String sessionId) {
        AgentMemoryJobState existing = jobStateMapper.selectByAgentIdAndSessionId(agentId, sessionId);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentMemoryJobState state = AgentMemoryJobState.builder()
                .agentId(agentId)
                .sessionId(sessionId)
                .processedUserMessageCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        jobStateMapper.insert(state);
        AgentMemoryJobState created = jobStateMapper.selectByAgentIdAndSessionId(agentId, sessionId);
        return created == null ? state : created;
    }

    private List<ChatMessage> loadConversationBatch(
            String sessionId,
            ChatMessage firstUserMessage,
            ChatMessage lastUserMessage,
            int interval
    ) {
        List<ChatMessage> messages = chatMessageMapper.selectUserAssistantMessagesAfter(
                sessionId,
                firstUserMessage.getCreatedAt(),
                interval * 4 + 20
        );
        List<ChatMessage> result = new ArrayList<>();
        boolean reachedLastUser = false;
        boolean hasAssistantAfterLastUser = false;
        for (ChatMessage message : messages) {
            if ("user".equals(message.getRole())) {
                if (reachedLastUser) {
                    break;
                }
                result.add(message);
                reachedLastUser = lastUserMessage.getId().equals(message.getId());
                continue;
            }
            if (!"assistant".equals(message.getRole())) {
                continue;
            }
            result.add(message);
            if (reachedLastUser && message.getCreatedAt().isAfter(lastUserMessage.getCreatedAt())) {
                hasAssistantAfterLastUser = true;
            }
        }
        if (!hasAssistantAfterLastUser) {
            throw new IllegalStateException("最新一批用户消息后的 assistant 回复尚未入库，等待下次自动记忆触发重试");
        }
        return result;
    }

    private String renderConversation(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (ChatMessage message : messages) {
            if (!StringUtils.hasText(message.getContent())) {
                continue;
            }
            sb.append("[")
                    .append(index++)
                    .append("] ")
                    .append(message.getRole())
                    .append(": ")
                    .append(message.getContent().trim())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private List<ExtractedMemory> extractMemories(Agent agent, String conversationText) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (chatClient == null) {
            throw new IllegalStateException("未找到自动记忆可用的 ChatClient: " + agent.getModel());
        }
        String response = chatClient
                .prompt()
                .system(buildMemoryExtractionSystemPrompt())
                .user(buildMemoryExtractionUserMessage(agent, conversationText))
                .call()
                .content();
        MemoryExtractionResult result = parseExtractionResult(response);
        if (result == null || result.getMemories() == null) {
            return List.of();
        }
        return result.getMemories().stream()
                .filter(this::isValidMemory)
                .limit(MAX_RETURNED_MEMORIES)
                .toList();
    }

    private MemoryExtractionResult parseExtractionResult(String response) {
        if (!StringUtils.hasText(response)) {
            return new MemoryExtractionResult();
        }
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("(?s)^```(?:json)?\\s*", "")
                    .replaceFirst("(?s)\\s*```$", "")
                    .trim();
        }
        try {
            return objectMapper.readValue(json, MemoryExtractionResult.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("自动记忆模型输出不是合法 JSON: " + response, e);
        }
    }

    private boolean isValidMemory(ExtractedMemory memory) {
        if (memory == null) {
            return false;
        }
        memory.normalize();
        if (!VALID_MEMORY_SCOPES.contains(memory.getMemoryScope())) {
            return false;
        }
        if (!VALID_MEMORY_TYPES.contains(memory.getMemoryType())) {
            return false;
        }
        if (!StringUtils.hasText(memory.getTitle()) || !StringUtils.hasText(memory.getContent())) {
            return false;
        }
        return !containsSensitiveText(memory.getTitle()) && !containsSensitiveText(memory.getContent());
    }

    private void writeMemories(String agentId, String sourceMessageId, List<ExtractedMemory> memories) {
        for (ExtractedMemory memory : memories) {
            if (agentMemoryMapper.countExactDuplicate(
                    agentId,
                    memory.getMemoryScope(),
                    memory.getTitle(),
                    memory.getContent()
            ) > 0) {
                continue;
            }
            CreateAgentMemoryRequest request = new CreateAgentMemoryRequest();
            request.setSourceMessageId(sourceMessageId);
            request.setMemoryScope(memory.getMemoryScope());
            request.setMemoryType(memory.getMemoryType());
            request.setTitle(memory.getTitle());
            request.setContent(memory.getContent());
            request.setPriority(0);
            request.setEnabled(true);
            agentMemoryFacadeService.createAgentMemory(agentId, request);
        }
    }

    private void updateJobState(String agentId, String sessionId, int processedCount, ChatMessage lastUserMessage) {
        jobStateMapper.updateProgress(
                agentId,
                sessionId,
                processedCount,
                lastUserMessage.getId(),
                lastUserMessage.getCreatedAt()
        );
    }

    private boolean containsSensitiveText(String text) {
        return StringUtils.hasText(text) && SENSITIVE_PATTERN.matcher(text).find();
    }

    private int normalizeInterval(Integer interval) {
        if (interval == null) {
            return DEFAULT_AUTO_MEMORY_INTERVAL;
        }
        return Math.max(MIN_AUTO_MEMORY_INTERVAL, Math.min(MAX_AUTO_MEMORY_INTERVAL, interval));
    }

    private String buildMemoryExtractionUserMessage(Agent agent, String conversationText) {
        String systemPrompt = StringUtils.hasText(agent.getSystemPrompt())
                ? agent.getSystemPrompt()
                : "未配置额外系统指令";
        return """
                【当前 Agent 名称】
                %s

                【当前 Agent System Prompt】
                %s

                【待整理对话】
                %s
                """.formatted(agent.getName(), systemPrompt, conversationText);
    }

    private String buildMemoryExtractionSystemPrompt() {
        return """
                你是 JChatMind 的 Agent 记忆整理器，只负责从一段对话中提炼当前 Agent 的长期记忆。

                你不会回答用户。
                你只输出严格 JSON，不输出 Markdown，不输出解释性文字。

                【任务】
                阅读给定的一段对话，提取值得写入当前 Agent Memory 的长期信息。
                你可以返回 0 条、1 条或多条 memory。
                如果没有长期价值，返回空数组。

                【Agent Memory 分类标准】

                Core Memory：
                用于保存当前 Agent 长期稳定的身份、职责、工作风格、硬性边界、强约定。
                Core Memory 会在该 Agent 的每一轮对话中固定注入。
                只有当信息会长期影响 Agent 是谁、如何工作、任务边界或固定行为时，才写入 core memory。

                Core Memory 示例：
                1. 用户明确要求该 Agent 长期遵守的偏好：
                   “以后你回答我代码问题时，优先用 Java。”
                   -> core memory
                2. 用户为该 Agent 设定的长期工作方式：
                   “你以后帮我改简历时，要更偏向后端开发岗位。”
                   -> core memory
                3. 用户明确指定这个 Agent 的长期任务边界：
                   “这个 Agent 以后专门帮我做面试复盘。”
                   -> core memory

                Retrieved Memory：
                用于保存当前 Agent 积累的历史经验、项目上下文、主题事实、讨论结论、低频背景。
                Retrieved Memory 不会每轮固定注入，只会在后续问题语义相关时被召回。
                如果信息只在某类问题中有帮助，而不会改变 Agent 的长期身份或边界，应写入 retrieved memory。

                Retrieved Memory 示例：
                1. 某次对话中的项目背景：
                   “用户的 AgentX 项目用了 SpringAI、PostgreSQL、pgvector 和 React。”
                   -> retrieved memory
                2. 某个阶段性目标：
                   “用户最近在准备 RAG 和 Agent 记忆系统相关的简历亮点。”
                   -> retrieved memory
                3. 某个可被未来问题召回的事实：
                   “用户上传过一本小林 MySQL PDF 到知识库里做测试。”
                   -> retrieved memory

                不要写入记忆的内容：
                - 普通寒暄、闲聊、无长期价值的表达。
                - 一次性问题或一次性回答。
                - 临时约束，例如“这次请用英文回答”。
                - 敏感信息，包括密码、API Key、token、密钥、身份证号、银行卡号等。
                - 模型回答中的不确定猜测。
                - 无法独立理解、离开当前上下文就失去意义的内容。

                【提取规则】
                1. 只提取与当前 Agent 有关的长期记忆。
                2. 不要保存用户全局偏好，除非用户明确说明只适用于当前 Agent。
                3. 不要把完整对话摘要写入记忆，应提炼成可长期复用的独立事实或约定。
                4. content 必须是完整、独立、可长期复用的陈述句。
                5. 对于重复、相近、低价值信息，应合并或忽略。
                6. 如果不确定是否有长期价值，默认不写入。
                7. 最多返回 5 条 memory。

                【输出 JSON schema】
                {
                  "memories": [
                    {
                      "memoryScope": "core 或 retrieved",
                      "memoryType": "fact / preference / decision / issue / task / feedback",
                      "title": "不超过 30 个中文字符",
                      "content": "可直接写入数据库的长期记忆",
                      "reason": "简短说明为什么值得保存"
                    }
                  ]
                }

                【输出约束】
                - 只能输出 JSON 对象。
                - 不要输出 Markdown。
                - 不要输出代码块。
                - 没有可保存记忆时输出 {"memories": []}。
                - memoryScope 只能是 core 或 retrieved。
                - memoryType 只能是 fact、preference、decision、issue、task、feedback。
                - title 和 content 不能包含敏感信息。
                """;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemoryExtractionResult {
        private List<ExtractedMemory> memories = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExtractedMemory {
        private String memoryScope;
        private String memoryType;
        private String title;
        private String content;
        private String reason;

        public void normalize() {
            this.memoryScope = normalizeLower(memoryScope);
            this.memoryType = normalizeLower(memoryType);
            this.title = title == null ? null : title.trim();
            this.content = content == null ? null : content.trim();
        }

        private String normalizeLower(String value) {
            return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
