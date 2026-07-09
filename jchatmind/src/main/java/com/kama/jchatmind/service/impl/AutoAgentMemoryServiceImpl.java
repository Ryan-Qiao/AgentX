package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.AgentMemoryJobStateMapper;
import com.kama.jchatmind.mapper.AgentMemoryMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.AgentMemoryJobState;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.entity.AgentMemory;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.request.CreateAgentMemoryRequest;
import com.kama.jchatmind.model.request.CreateUserMemoryRequest;
import com.kama.jchatmind.model.request.UpdateAgentMemoryRequest;
import com.kama.jchatmind.model.request.UpdateUserMemoryRequest;
import com.kama.jchatmind.service.AgentMemoryFacadeService;
import com.kama.jchatmind.service.AutoAgentMemoryService;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

@Slf4j
@Service
@AllArgsConstructor
public class AutoAgentMemoryServiceImpl implements AutoAgentMemoryService {
    private static final int DEFAULT_AUTO_MEMORY_INTERVAL = 10;
    private static final int MIN_AUTO_MEMORY_INTERVAL = 3;
    private static final int MAX_AUTO_MEMORY_INTERVAL = 50;
    private static final int MAX_RETURNED_MEMORIES = 5;
    private static final Set<String> VALID_MEMORY_ACTIONS = Set.of("create", "update", "ignore");
    private static final Set<String> VALID_MEMORY_TARGETS = Set.of("agent", "user");
    private static final Set<String> VALID_MEMORY_SCOPES = Set.of("core", "retrieved");
    private static final Set<String> VALID_MEMORY_TYPES = Set.of("fact", "preference", "decision", "issue", "task", "feedback");
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|passwd|密码|密钥|身份证|银行卡)"
    );

    private final AgentMapper agentMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentMemoryMapper agentMemoryMapper;
    private final UserMemoryMapper userMemoryMapper;
    private final AgentMemoryJobStateMapper jobStateMapper;
    private final AgentMemoryFacadeService agentMemoryFacadeService;
    private final UserMemoryFacadeService userMemoryFacadeService;
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

        List<UserMemory> existingUserMemories = userMemoryMapper.selectAll();
        List<AgentMemory> existingAgentMemories = agentMemoryMapper.selectByAgentId(agentId);
        List<MemoryOperation> operations = extractMemoryOperations(
                agent,
                promptText,
                existingUserMemories,
                existingAgentMemories
        );
        writeMemoryOperations(agentId, lastUserMessage.getId(), operations, existingUserMemories, existingAgentMemories);
        updateJobState(agentId, sessionId, processedCount + interval, lastUserMessage);
        log.info("自动记忆整理完成: agentId={}, sessionId={}, userMessages={}, operations={}",
                agentId,
                sessionId,
                interval,
                operations.size());
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

    private List<MemoryOperation> extractMemoryOperations(
            Agent agent,
            String conversationText,
            List<UserMemory> existingUserMemories,
            List<AgentMemory> existingAgentMemories
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (chatClient == null) {
            throw new IllegalStateException("未找到自动记忆可用的 ChatClient: " + agent.getModel());
        }
        String response = chatClient
                .prompt()
                .system(buildMemoryExtractionSystemPrompt())
                .user(buildMemoryExtractionUserMessage(
                        agent,
                        conversationText,
                        existingUserMemories,
                        existingAgentMemories
                ))
                .call()
                .content();
        MemoryExtractionResult result = parseExtractionResult(response);
        if (result == null || result.getOperations() == null) {
            return List.of();
        }
        return result.getOperations().stream()
                .filter(this::isValidOperation)
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

    private boolean isValidOperation(MemoryOperation operation) {
        if (operation == null) {
            return false;
        }
        operation.normalize();
        if (!VALID_MEMORY_ACTIONS.contains(operation.getAction())) {
            return false;
        }
        if ("ignore".equals(operation.getAction())) {
            return true;
        }
        if (!VALID_MEMORY_TARGETS.contains(operation.getMemoryTarget())) {
            return false;
        }
        if ("agent".equals(operation.getMemoryTarget()) && !VALID_MEMORY_SCOPES.contains(operation.getMemoryScope())) {
            return false;
        }
        if ("update".equals(operation.getAction()) && !StringUtils.hasText(operation.getExistingMemoryId())) {
            return false;
        }
        if (!VALID_MEMORY_TYPES.contains(operation.getMemoryType())) {
            return false;
        }
        if (!StringUtils.hasText(operation.getTitle()) || !StringUtils.hasText(operation.getContent())) {
            return false;
        }
        return !containsSensitiveText(operation.getTitle()) && !containsSensitiveText(operation.getContent());
    }

    private void writeMemoryOperations(
            String agentId,
            String sourceMessageId,
            List<MemoryOperation> operations,
            List<UserMemory> existingUserMemories,
            List<AgentMemory> existingAgentMemories
    ) {
        Map<String, UserMemory> userMemoryById = existingUserMemories.stream()
                .filter(memory -> StringUtils.hasText(memory.getId()))
                .collect(Collectors.toMap(UserMemory::getId, Function.identity(), (left, right) -> left));
        Map<String, AgentMemory> agentMemoryById = existingAgentMemories.stream()
                .filter(memory -> StringUtils.hasText(memory.getId()))
                .collect(Collectors.toMap(AgentMemory::getId, Function.identity(), (left, right) -> left));

        for (MemoryOperation operation : operations) {
            if ("ignore".equals(operation.getAction())) {
                continue;
            }
            if ("update".equals(operation.getAction())) {
                updateExistingMemory(operation, userMemoryById, agentMemoryById);
                continue;
            }
            if ("user".equals(operation.getMemoryTarget())) {
                createUserMemory(sourceMessageId, operation);
            } else {
                createAgentMemory(agentId, sourceMessageId, operation);
            }
        }
    }

    private void createAgentMemory(String agentId, String sourceMessageId, MemoryOperation operation) {
        if (agentMemoryMapper.countExactDuplicate(
                agentId,
                operation.getMemoryScope(),
                operation.getTitle(),
                operation.getContent()
        ) > 0) {
            return;
        }
        CreateAgentMemoryRequest request = new CreateAgentMemoryRequest();
        request.setSourceMessageId(sourceMessageId);
        request.setMemoryScope(operation.getMemoryScope());
        request.setMemoryType(operation.getMemoryType());
        request.setTitle(operation.getTitle());
        request.setContent(operation.getContent());
        request.setPriority(0);
        request.setEnabled(true);
        agentMemoryFacadeService.createAgentMemory(agentId, request);
    }

    private void createUserMemory(String sourceMessageId, MemoryOperation operation) {
        if (userMemoryMapper.countExactDuplicate(
                operation.getTitle(),
                operation.getContent()
        ) > 0) {
            return;
        }

        CreateUserMemoryRequest request = new CreateUserMemoryRequest();
        request.setSourceMessageId(sourceMessageId);
        request.setMemoryType(operation.getMemoryType());
        request.setTitle(operation.getTitle());
        request.setContent(operation.getContent());
        request.setPriority(0);
        request.setConfidence(BigDecimal.ONE);
        request.setEnabled(true);
        userMemoryFacadeService.createUserMemory(request);
    }

    private void updateExistingMemory(
            MemoryOperation operation,
            Map<String, UserMemory> userMemoryById,
            Map<String, AgentMemory> agentMemoryById
    ) {
        if ("user".equals(operation.getMemoryTarget())) {
            if (!userMemoryById.containsKey(operation.getExistingMemoryId())) {
                return;
            }
            UpdateUserMemoryRequest request = new UpdateUserMemoryRequest();
            request.setMemoryType(operation.getMemoryType());
            request.setTitle(operation.getTitle());
            request.setContent(operation.getContent());
            request.setConfidence(BigDecimal.ONE);
            request.setEnabled(true);
            userMemoryFacadeService.updateUserMemory(operation.getExistingMemoryId(), request);
            return;
        }

        if (!agentMemoryById.containsKey(operation.getExistingMemoryId())) {
            return;
        }
        UpdateAgentMemoryRequest request = new UpdateAgentMemoryRequest();
        request.setMemoryScope(operation.getMemoryScope());
        request.setMemoryType(operation.getMemoryType());
        request.setTitle(operation.getTitle());
        request.setContent(operation.getContent());
        request.setEnabled(true);
        agentMemoryFacadeService.updateAgentMemory(operation.getExistingMemoryId(), request);
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

    private String buildMemoryExtractionUserMessage(
            Agent agent,
            String conversationText,
            List<UserMemory> existingUserMemories,
            List<AgentMemory> existingAgentMemories
    ) {
        String systemPrompt = StringUtils.hasText(agent.getSystemPrompt())
                ? agent.getSystemPrompt()
                : "未配置额外系统指令";
        return """
                【当前 Agent 名称】
                %s

                【当前 Agent System Prompt】
                %s

                【已有 User Memory】
                %s

                【当前 Agent 已有 Agent Memory】
                %s

                【待整理对话】
                %s
                """.formatted(
                agent.getName(),
                systemPrompt,
                renderExistingUserMemories(existingUserMemories),
                renderExistingAgentMemories(existingAgentMemories),
                conversationText
        );
    }

    private String renderExistingUserMemories(List<UserMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (UserMemory memory : memories) {
            if (!StringUtils.hasText(memory.getId()) || !StringUtils.hasText(memory.getContent())) {
                continue;
            }
            sb.append("- [id=")
                    .append(memory.getId())
                    .append("] ")
                    .append(nullToEmpty(memory.getTitle()))
                    .append("：")
                    .append(memory.getContent().trim())
                    .append("\n");
        }
        return StringUtils.hasText(sb.toString()) ? sb.toString().trim() : "无";
    }

    private String renderExistingAgentMemories(List<AgentMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentMemory memory : memories) {
            if (!StringUtils.hasText(memory.getId()) || !StringUtils.hasText(memory.getContent())) {
                continue;
            }
            sb.append("- [id=")
                    .append(memory.getId())
                    .append("][scope=")
                    .append(nullToEmpty(memory.getMemoryScope()))
                    .append("] ")
                    .append(nullToEmpty(memory.getTitle()))
                    .append("：")
                    .append(memory.getContent().trim())
                    .append("\n");
        }
        return StringUtils.hasText(sb.toString()) ? sb.toString().trim() : "无";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String buildMemoryExtractionSystemPrompt() {
        return """
                你是 JChatMind 的自动记忆整理器，只负责从一段对话中提炼长期记忆。

                你不会回答用户。
                你只输出严格 JSON，不输出 Markdown，不输出解释性文字。

                【任务】
                阅读给定的一段对话，并结合已有 User Memory 与当前 Agent 已有 Agent Memory，判断是否需要创建、更新或忽略记忆。
                是否创建、是否更新哪条已有记忆、记忆归属是 User Memory 还是 Agent Memory，必须全部由你根据语义判断，并通过结构化 JSON 输出。
                你可以返回 0 条、1 条或多条 operation。
                如果没有需要处理的记忆，返回空数组。

                【User Memory 分类标准】

                User Memory：
                用于保存用户本人长期稳定的信息、偏好、背景、目标、称呼习惯等。
                User Memory 会被所有 Agent 共享，在任意 Agent 后续对话中都可能被注入。
                当信息描述的是“用户是谁、用户长期偏好什么、用户长期目标或背景是什么”，并且不只服务于当前 Agent 时，应写入 user memory。

                User Memory 示例：
                1. 用户本人身份事实：
                   “我叫乔国宇。”
                   -> memoryTarget=user
                2. 用户跨 Agent 的长期偏好：
                   “以后代码问题优先用 Java 示例回答。”
                   -> memoryTarget=user
                3. 用户长期目标或背景：
                   “我现在主要准备后端面试。”
                   -> memoryTarget=user
                4. 用户称呼偏好：
                   “以后叫我少爷。”
                   -> memoryTarget=user

                【Agent Memory 分类标准】

                Core Memory：
                用于保存当前 Agent 长期稳定的身份、职责、工作风格、硬性边界、强约定。
                Core Memory 会在该 Agent 的每一轮对话中固定注入。
                只有当信息会长期影响 Agent 是谁、如何工作、任务边界或固定行为时，才写入 core memory。

                Core Memory 示例：
                1. 用户为当前 Agent 设定的长期工作方式：
                   “你以后帮我改简历时，要更偏向后端开发岗位。”
                   -> memoryTarget=agent, memoryScope=core
                2. 用户明确指定当前 Agent 的长期任务边界：
                   “这个 Agent 以后专门帮我做面试复盘。”
                   -> memoryTarget=agent, memoryScope=core

                Retrieved Memory：
                用于保存当前 Agent 积累的历史经验、项目上下文、主题事实、讨论结论、低频背景。
                Retrieved Memory 不会每轮固定注入，只会在后续问题语义相关时被召回。
                如果信息只在某类问题中有帮助，而不会改变 Agent 的长期身份或边界，应写入 retrieved memory。

                Retrieved Memory 示例：
                1. 某次对话中的项目背景：
                   “用户的 AgentX 项目用了 SpringAI、PostgreSQL、pgvector 和 React。”
                   -> memoryTarget=agent, memoryScope=retrieved
                2. 某个阶段性目标：
                   “用户最近在准备 RAG 和 Agent 记忆系统相关的简历亮点。”
                   -> memoryTarget=agent, memoryScope=retrieved
                3. 某个可被未来问题召回的事实：
                   “用户上传过一本小林 MySQL PDF 到知识库里做测试。”
                   -> memoryTarget=agent, memoryScope=retrieved

                不要写入记忆的内容：
                - 普通寒暄、闲聊、无长期价值的表达。
                - 一次性问题或一次性回答。
                - 临时约束，例如“这次请用英文回答”。
                - 敏感信息，包括密码、API Key、token、密钥、身份证号、银行卡号等。
                - 模型回答中的不确定猜测。
                - 无法独立理解、离开当前上下文就失去意义的内容。

                【提取规则】
                1. 必须先判断 action：create、update 或 ignore。
                2. create：没有已有记忆覆盖该长期信息，需要新增。
                3. update：用户明确更正、替换或补充某条已有记忆，必须填写 existingMemoryId。
                4. ignore：已有记忆已经覆盖、信息没有长期价值、信息不确定或不应保存。
                5. memoryTarget=user 时，不需要 memoryScope；memoryScope 可以为空。
                6. memoryTarget=agent 时，必须判断 memoryScope：core 或 retrieved。
                7. update 时 existingMemoryId 必须来自输入中的已有 User Memory 或当前 Agent 已有 Agent Memory，禁止编造 ID。
                8. memoryTarget=user 只能 update 已有 User Memory；memoryTarget=agent 只能 update 当前 Agent 已有 Agent Memory。
                9. 不要把完整对话摘要写入记忆，应提炼成可长期复用的独立事实或约定。
                10. content 必须是完整、独立、可长期复用的陈述句。
                11. 对于同一批对话中的更正或纠错，以用户最后一次明确表述为准。
                12. 对于重复、相近、低价值信息，应合并或忽略。
                13. 如果不确定是否有长期价值，默认 ignore。
                14. 最多返回 5 条 operation。

                【输出 JSON schema】
                {
                  "operations": [
                    {
                      "action": "create / update / ignore",
                      "memoryTarget": "user 或 agent",
                      "existingMemoryId": "action=update 时填写已有记忆 id，否则为空字符串",
                      "memoryScope": "当 memoryTarget=agent 时为 core 或 retrieved；当 memoryTarget=user 时为空字符串",
                      "memoryType": "fact / preference / decision / issue / task / feedback",
                      "title": "不超过 30 个中文字符",
                      "content": "create/update 时可直接写入数据库的长期记忆",
                      "reason": "简短说明为什么 create/update/ignore"
                    }
                  ]
                }

                【输出约束】
                - 只能输出 JSON 对象。
                - 不要输出 Markdown。
                - 不要输出代码块。
                - 没有可处理记忆时输出 {"operations": []}。
                - action 只能是 create、update 或 ignore。
                - memoryTarget 只能是 user 或 agent。
                - memoryTarget=agent 时 memoryScope 只能是 core 或 retrieved。
                - memoryTarget=user 时 memoryScope 输出空字符串。
                - memoryType 只能是 fact、preference、decision、issue、task、feedback。
                - title 和 content 不能包含敏感信息。
                """;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemoryExtractionResult {
        private List<MemoryOperation> operations = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MemoryOperation {
        private String action;
        private String memoryTarget;
        private String existingMemoryId;
        private String memoryScope;
        private String memoryType;
        private String title;
        private String content;
        private String reason;

        public void normalize() {
            this.action = normalizeLower(action);
            this.memoryTarget = normalizeLower(memoryTarget);
            this.existingMemoryId = existingMemoryId == null ? null : existingMemoryId.trim();
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
