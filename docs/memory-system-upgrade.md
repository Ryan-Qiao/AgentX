# JChatMind 记忆系统升级技术方案

## 1. 背景和目标

JChatMind 当前是一个前后端网页对话项目，核心产品模型不是“一个代码执行 Agent 长期维护某个工程目录”，而是：

```text
用户
  ↓
创建多个 Agent
  ↓
每个 Agent 下有多个 Chat Session
  ↓
每个 Chat Session 下有多轮 Message
```

因此，记忆系统不能直接照搬 Hermes 的 `USER.md / MEMORY.md / Skills` 文件结构。JChatMind 可以参考 Hermes 的思想，但必须重新锚定到自己的产品结构：

```text
会话内短期记忆
+
用户级长期记忆
+
Agent 级长期记忆
+
会话历史搜索
+
技能/流程记忆
+
未来可选 Workspace 记忆
```

一句话目标：

> 让 JChatMind 从“只记住当前会话最近 N 条消息”的聊天系统，升级为“每个 Agent 能跨多个对话积累经验，同时保留用户全局偏好和可复用任务流程”的 Agent 对话平台。

## 2. 设计原则

### 2.1 按归属范围划分，而不是按听起来高级的概念划分

`Long-term Memory` 不是一个独立的业务层。它描述的是“长期保存、跨会话可用”的性质。

在 JChatMind 中：

- `User Memory` 是长期记忆。
- `Agent Memory` 也是长期记忆。
- 未来的 `Workspace Memory` 也是长期记忆。

因此文档和实现中不再把 `Long-term Memory` 单独作为与 `User Memory / Agent Memory` 平级的层，而是明确拆分到具体归属范围。

### 2.2 Agent Memory 是本项目最核心的长期记忆

JChatMind 的核心使用方式是“用户创建 Agent，然后在该 Agent 下开启多个对话”。所以跨对话记忆最自然的归属不是全局用户，也不是项目，而是某个 Agent。

例子：

```text
Agent：Java 面试官
  - 记住用户正在准备 Java 后端面试
  - 记住用户薄弱点是 JVM、并发、Spring
  - 记住上次模拟面试反馈

Agent：英语老师
  - 记住用户英语水平
  - 记住用户常错语法
  - 记住用户希望练口语

Agent：数据库助手
  - 记住用户常用 PostgreSQL
  - 记住用户喜欢先看 SQL 再解释结果
```

这些信息不应该放到全局 `User Memory`，因为它们只在特定 Agent 的上下文里成立。

### 2.3 User Memory 只保存所有 Agent 都应该知道的偏好

`User Memory` 应该保持小而精，只记录跨 Agent 都有效的稳定信息。

适合保存：

- 用户偏好中文回答。
- 用户喜欢先给结论，再解释原因。
- 用户是 Java 后端方向。
- 用户希望回答能结合面试表达。
- 用户偏好实际验证结果，而不是只给理论方案。

不适合保存：

- 某个 Agent 专属的任务状态。
- 某次临时测试。
- 某个会话里的上下文细节。
- 很快过期的信息。

### 2.4 Workspace Memory 是未来可选能力

当前 JChatMind 还没有工作空间/项目空间概念，所以不建议把 `Project Memory` 作为必选核心层。

如果未来产品支持：

```text
工作空间：毕业论文
工作空间：Java 面试准备
工作空间：JChatMind 项目
工作空间：公司日报助手
```

那时可以引入 `Workspace Memory`，保存某个工作空间下多个 Agent 共享的背景上下文。

## 3. 当前实现现状

当前记忆链路：

```text
用户发送消息
  ↓
ChatMessageFacadeService.createChatMessage()
  ↓
写入 chat_message
  ↓
发布 ChatEvent
  ↓
JChatMindFactory.create()
  ↓
loadMemory(sessionId)
  ↓
读取最近 messageLength 条 chat_message
  ↓
转换成 Spring AI Message
  ↓
MessageWindowChatMemory
  ↓
模型回答 / 工具调用
  ↓
assistant/tool 消息继续写回 chat_message
```

当前优点：

- 会话消息已持久化，服务重启后不丢失。
- 同一会话内可以使用最近上下文。
- 工具调用过程也会保存，包括 `toolCalls` 和 `toolResponse`。
- `messageLength` 已作为 Agent 配置项。

当前限制：

- 只有 `Session Memory`，没有 `User Memory` 和 `Agent Memory`。
- 同一个 Agent 下的不同会话之间不能共享长期上下文。
- 超过 `messageLength` 的旧消息不会进入模型上下文。
- 没有按需搜索历史会话的能力。
- 没有记忆写入筛选机制，无法判断哪些内容值得长期保存。
- 没有 Skills 过程记忆，重复任务不会沉淀成流程。

## 4. 目标记忆模型

推荐的 JChatMind 记忆层：

| 记忆层 | 归属范围 | 是否建议做 | 作用 |
| --- | --- | --- | --- |
| `Session Memory` | 某个 Chat Session | 已有，继续保留 | 当前会话最近 N 条消息，解决连续对话 |
| `User Memory` | 某个用户 | 强烈建议 | 所有 Agent 共享的用户偏好、沟通习惯、背景 |
| `Agent Memory` | 某个 Agent | 强烈建议，最核心 | Agent 跨多个会话积累的长期事实、偏好、任务状态 |
| `Session History Search` | Agent 或用户范围 | 建议 | 按需检索历史对话，不把全部历史塞进 prompt |
| `Skill Memory` | Agent 或全局 | 建议作为亮点 | 记“事情怎么做”的可复用流程 |
| `Workspace Memory` | 某个工作空间 | 未来可选 | 多个 Agent 共享的项目/主题背景 |

最终结构：

```text
JChatMind Memory
  ├── Session Memory
  ├── User Memory
  ├── Agent Memory
  ├── Session History Search
  ├── Skill Memory
  └── Workspace Memory（未来可选）
```

## 5. Agent 上下文构建流程

升级后的每轮对话流程：

```text
用户输入
  ↓
保存 user message 到 chat_message
  ↓
加载当前 Session Memory
  └── 最近 N 条 chat_message
  ↓
加载 User Memory
  └── 用户级长期偏好
  ↓
加载 Agent Memory
  ├── Agent 核心记忆
  └── 当前问题相关的 Agent 长期记忆
  ↓
按需搜索历史会话
  └── 当前 Agent 下相关历史片段
  ↓
按需检索 Skill
  └── 与当前任务相关的流程记忆
  ↓
组合 Prompt
  ↓
Think-Execute Loop
  ↓
保存 assistant/tool message
  ↓
异步判断是否写入 User Memory / Agent Memory / Skill Memory
```

重要规则：

- `Session Memory` 永远只解决当前会话连续性。
- `User Memory` 只保存跨 Agent 有效的信息。
- `Agent Memory` 保存某个 Agent 自己跨会话需要记住的信息。
- 历史会话只按需检索，不默认全部进入上下文。
- Skill 是过程记忆，不是事实记忆。

## 6. 记忆类型定义

### 6.1 Session Memory

当前已有，继续保留。

来源：

- `chat_message`

范围：

- 当前 `chatSessionId`

加载方式：

- `selectBySessionIdRecently(sessionId, messageLength)`
- 按 `created_at DESC LIMIT N` 取最近 N 条，再按时间正序恢复。

作用：

- 处理“继续”“刚才那个”“上面提到的内容”等会话内指代。

改造建议：

- 保留现有实现。
- 增加工具消息成对保护，避免 assistant tool call 被加载但对应 tool response 被截断。
- 后续可以对超长工具结果做压缩摘要。

### 6.2 User Memory

用户级长期记忆，所有 Agent 都可参考。

适合记录：

- 用户喜欢中文回答。
- 用户偏好简洁、先结论后解释。
- 用户技术方向是 Java 后端。
- 用户关注项目亮点、面试表达、工程落地。
- 用户希望关键功能要实际验证。

不适合记录：

- 某个 Agent 专属任务。
- 某个会话里的临时上下文。
- 临时测试数据。
- 密钥、密码、Token。

注入策略：

- 默认每轮注入少量高优先级 User Memory。
- 控制数量，例如最多 5 到 10 条。
- 如果和用户最新输入冲突，以最新输入为准。

### 6.3 Agent Memory

Agent 级长期记忆，是本项目最核心的升级点。

范围：

- 绑定 `agent_id`
- 跨该 Agent 下的多个 Chat Session 生效

适合记录：

- 这个 Agent 的长期目标。
- 用户在这个 Agent 场景下的偏好。
- 该 Agent 已经完成过的重要任务。
- 该 Agent 对用户能力、需求、上下文的长期理解。
- 该 Agent 反复遇到的问题和有效解决方式。

例子：

```text
Agent：Java 面试官
- 用户正在准备 Java 后端面试。
- 用户希望每次回答都带面试表达。
- 用户并发和 JVM 基础较薄弱。

Agent：写作助手
- 用户偏好正式但不生硬的中文表达。
- 用户经常需要 1000 字左右的城市/行业报告。

Agent：数据库助手
- 用户常用 PostgreSQL。
- 用户希望查询结果先给结论，再解释 SQL。
```

Agent Memory 可以分两类：

```text
Agent Core Memory
  小而精，默认注入

Agent Retrieved Memory
  数量更多，按当前问题检索注入
```

### 6.4 Session History Search

历史对话搜索不是独立长期记忆，而是对已存在 `chat_message` 的按需召回能力。

触发场景：

- 用户说“上次”“之前”“刚才那个会话”“我们之前聊过的”。
- 当前问题需要引用旧会话中的结论。
- Agent Memory 不足以回答，需要查历史过程。

检索范围建议：

- 默认优先搜索当前 Agent 下的历史会话。
- 必要时再扩展到用户所有 Agent 的历史会话。

实现阶段：

1. 关键词搜索：`ILIKE` 或 PostgreSQL 全文检索。
2. 语义搜索：给消息或会话摘要生成 embedding，使用 pgvector。
3. 会话摘要搜索：先为每个会话生成 summary，再检索 summary。

### 6.5 Skill Memory

Skill Memory 是过程记忆，记录“某类事情怎么做”。

它不回答“用户是谁”，也不记录“这个 Agent 记住了什么事实”，而是记录可复用流程。

适合沉淀：

- 如何写一篇城市报告。
- 如何查询数据库并解释结果。
- 如何发送一封结构化邮件。
- 如何排查 Agent 工具调用没有返回给前端。
- 如何验证 Spring Boot + React 项目功能。

Skill 内容建议：

```text
名称
适用场景
触发关键词
步骤
验证方式
常见错误
示例输出
最后更新时间
```

### 6.6 Workspace Memory（未来可选）

当前不建议作为第一期核心能力。

只有当产品未来支持“工作空间/项目空间”时再引入。

例：

```text
Workspace：Java 面试准备
  ├── Agent：Java 面试官
  ├── Agent：简历优化助手
  └── Agent：算法练习助手
```

这时 Workspace Memory 可以保存这些 Agent 共享的背景：

- 用户目标是准备 Java 后端校招。
- 用户简历项目是 JChatMind。
- 面试重点是 Spring、JVM、数据库、LLM 应用。

## 7. 数据库设计建议

### 7.1 user_memory

保存用户级长期记忆。

```sql
CREATE TABLE user_memory (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NULL,
    memory_type varchar(32) NOT NULL,  -- preference / profile / communication / constraint
    title varchar(255) NOT NULL,
    content text NOT NULL,
    priority int NOT NULL DEFAULT 0,
    confidence numeric(4, 3) NOT NULL DEFAULT 1.0,
    enabled boolean NOT NULL DEFAULT true,
    source_message_id uuid NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now(),
    last_used_at timestamp NULL
);
```

当前项目如果还没有用户体系，可以先让 `user_id` 为空，后续接入登录后再补。

### 7.2 agent_memory

保存 Agent 级长期记忆。

```sql
CREATE TABLE agent_memory (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id uuid NOT NULL,
    session_id uuid NULL,
    source_message_id uuid NULL,
    memory_scope varchar(32) NOT NULL, -- core / retrieved
    memory_type varchar(32) NOT NULL,  -- preference / fact / decision / issue / task / feedback
    title varchar(255),
    content text NOT NULL,
    importance int NOT NULL DEFAULT 0,
    confidence numeric(4, 3) NOT NULL DEFAULT 1.0,
    embedding vector(1024),
    tags jsonb,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now(),
    last_used_at timestamp NULL
);
```

说明：

- `memory_scope=core`：少量高优先级 Agent 核心记忆，默认注入。
- `memory_scope=retrieved`：数量更多，通过 embedding 按需检索。
- `importance` 用于控制排序和是否长期保留。
- `confidence` 表示这条记忆有多确定。
- `source_message_id` 用于追溯来源。

### 7.3 chat_message_embedding

用于历史会话语义搜索，不建议直接把 embedding 放进 `chat_message` 主表。

```sql
CREATE TABLE chat_message_embedding (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id uuid NOT NULL,
    session_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    content text NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at timestamp NOT NULL DEFAULT now()
);
```

### 7.4 chat_session_summary

可选，用于更高质量的历史会话搜索。

```sql
CREATE TABLE chat_session_summary (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid NOT NULL,
    agent_id uuid NOT NULL,
    summary text NOT NULL,
    key_points jsonb,
    embedding vector(1024),
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now()
);
```

### 7.5 agent_skill

保存过程记忆。

```sql
CREATE TABLE agent_skill (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id uuid NULL,
    name varchar(255) NOT NULL,
    description text NOT NULL,
    trigger_keywords jsonb,
    content text NOT NULL,
    usage_count int NOT NULL DEFAULT 0,
    success_count int NOT NULL DEFAULT 0,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now(),
    last_used_at timestamp NULL
);
```

说明：

- `agent_id IS NULL` 表示全局 Skill。
- `agent_id IS NOT NULL` 表示某个 Agent 专属 Skill。

### 7.6 workspace_memory（未来可选）

如果未来支持工作空间，再增加。

```sql
CREATE TABLE workspace_memory (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid NOT NULL,
    title varchar(255) NOT NULL,
    content text NOT NULL,
    priority int NOT NULL DEFAULT 0,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now()
);
```

## 8. 后端模块设计

建议新增包：

```text
com.kama.jchatmind.memory
  ├── UserMemoryService
  ├── AgentMemoryService
  ├── SessionHistorySearchService
  ├── SkillMemoryService
  ├── MemoryContextBuilder
  ├── MemoryPromptRenderer
  ├── MemoryWriteService
  └── MemorySafetyFilter
```

### 8.1 MemoryContextBuilder

职责：

- 接收 `agentId`、`sessionId`、`latestUserMessage`。
- 加载当前会话短期消息。
- 加载 User Memory。
- 加载 Agent Core Memory。
- 检索 Agent Retrieved Memory。
- 按需检索历史会话。
- 检索相关 Skill。
- 输出结构化上下文。

示例：

```java
public record MemoryContext(
        List<Message> sessionMessages,
        List<UserMemoryDTO> userMemories,
        List<AgentMemoryDTO> agentCoreMemories,
        List<AgentMemoryDTO> agentRetrievedMemories,
        List<ChatHistorySnippetDTO> historySnippets,
        List<AgentSkillDTO> skills
) {}
```

### 8.2 MemoryPromptRenderer

职责：

- 将 `MemoryContext` 渲染成 prompt。
- 控制每类记忆的数量和长度。
- 明确不同记忆的权重和冲突规则。

建议结构：

```text
【用户长期偏好】
- ...

【当前 Agent 长期记忆】
- ...

【当前问题相关记忆】
- ...

【相关历史对话片段】
- ...

【可参考技能流程】
- ...
```

### 8.3 MemoryWriteService

职责：

- 在一轮 Agent 执行结束后，判断是否应该写入记忆。
- 决定写入 `user_memory`、`agent_memory` 或 `agent_skill`。
- 做去重、合并、冲突处理。

建议流程：

```text
收集本轮 conversation
  ↓
敏感信息过滤
  ↓
候选记忆抽取
  ↓
判断归属范围：User / Agent / Skill
  ↓
检索相似已有记忆
  ↓
新增 / 更新 / 忽略
```

### 8.4 MemorySafetyFilter

必须拦截：

- API Key
- Token
- 数据库密码
- 邮箱授权码
- 身份证/银行卡等敏感个人信息
- 明显的一次性临时信息

## 9. Agent Loop 接入点

当前 `JChatMindFactory.create()` 负责创建 Agent 运行实例。建议在这里接入记忆上下文：

```text
JChatMindFactory.create(agentId, sessionId)
  ↓
loadAgent(agentId)
  ↓
loadMemory(sessionId)                         -- 现有 Session Memory
  ↓
memoryContextBuilder.build(agentId, sessionId, latestUserMessage)
  ↓
memoryPromptRenderer.render(memoryContext)
  ↓
new JChatMind(..., renderedMemoryPrompt)
```

`JChatMind.think()` 的 system prompt 应增加：

```text
【记忆使用规则】
1. 用户长期偏好适用于所有 Agent，但如果用户最新输入有不同要求，以最新输入为准。
2. 当前 Agent 长期记忆只适用于当前 Agent，不要泛化到其他 Agent。
3. 相关历史片段只是检索结果，不代表用户当前请求。
4. Skill 是可参考流程，不是必须逐字执行。
5. 不要主动暴露内部记忆内容，除非用户要求查看。
```

`JChatMind.run()` 完成后异步触发：

```text
MemoryWriteService.reviewConversation(agentId, sessionId)
```

## 10. 写入策略

### 10.1 写入 User Memory

写入条件：

- 用户明确表达长期偏好。
- 该偏好对所有 Agent 都有帮助。
- 内容稳定，不依赖某个具体任务。

例：

```text
以后都用中文回答。
回答先给结论，再给原因。
我主要是 Java 后端方向。
```

### 10.2 写入 Agent Memory

写入条件：

- 信息只在当前 Agent 的角色和任务范围内有意义。
- 能帮助该 Agent 后续跨会话服务用户。
- 是任务进展、长期目标、反馈、偏好或重要结论。

例：

```text
在“Java 面试官”Agent 中：
- 用户正在准备 Java 后端面试。
- 用户希望模拟面试后给评分和改进建议。
- 用户最近重点复习 JVM。
```

### 10.3 写入 Skill Memory

写入条件：

- 出现可复用流程。
- 该流程未来可能反复使用。
- 流程具有明确步骤和验证方式。

例：

```text
如何验证 Agent 工具调用：
1. 创建临时 Agent。
2. 创建临时 Session。
3. 发送触发工具的消息。
4. 轮询 chat_message。
5. 检查 assistant.toolCalls 和 tool response。
6. 清理临时数据。
```

### 10.4 不写入

- 一次性闲聊。
- 临时测试。
- 无长期价值的问题。
- 很快过期的信息。
- 密钥、密码、Token。
- 大段原始聊天全文。
- 未经确认的模型推测。

## 11. 检索策略

### 11.1 User Memory 注入

- 默认注入少量高优先级 User Memory。
- 数量建议 5 到 10 条。
- 不做复杂检索，除非 User Memory 很多。

### 11.2 Agent Memory 注入

分两段：

```text
Agent Core Memory
  默认注入，数量少，优先级高

Agent Retrieved Memory
  根据 latestUserMessage 做 embedding 检索
```

推荐排序：

```text
score = similarity * 0.6 + importance * 0.3 + recency * 0.1
```

### 11.3 历史会话搜索

默认不每轮搜索全部历史，避免成本和噪声。

触发条件：

- 用户出现“上次”“之前”“历史”“那个会话”等表达。
- 当前任务需要找过去做过的结论。
- Agent Memory 中没有足够信息。

### 11.4 Skill 检索

第一阶段可用关键词匹配：

- “写报告” -> 写作报告 Skill
- “查数据库” -> 数据库查询 Skill
- “发邮件” -> 邮件生成/发送 Skill
- “验证工具调用” -> Agent 工具调用验证 Skill

后续可加 embedding 检索。

## 12. 前端改造目标

### 12.1 Agent 详情页

新增：

- Agent Memory 开关。
- Agent Core Memory 列表。
- Agent Retrieved Memory 管理入口。
- 是否允许自动写入记忆。

### 12.2 聊天页面

新增：

- “本轮使用了哪些记忆”的调试面板。
- 用户可点击查看相关历史片段。
- 用户可手动让 Agent “记住这件事”。

### 12.3 用户设置页

新增：

- User Memory 管理。
- 查看、编辑、禁用、删除全局偏好。

### 12.4 Skill 管理页

新增：

- Skill 列表。
- Skill 创建/编辑/禁用。
- 使用次数、成功次数展示。

## 13. 分阶段实施路线

### Phase 1：Agent Memory MVP

目标：

- 先解决“同一个 Agent 多个会话之间不能共享记忆”的核心问题。

任务：

- 新增 `agent_memory` 表。
- 实现 `AgentMemoryService`。
- 支持手动写入 Agent Core Memory。
- 在 `JChatMindFactory` 构造上下文时注入 Agent Core Memory。

验收：

- 在某个 Agent 中写入一条记忆后，新建该 Agent 的另一个对话也能使用。
- 其他 Agent 不会看到这条记忆。

### Phase 2：User Memory

目标：

- 增加所有 Agent 共享的用户偏好。

任务：

- 新增 `user_memory` 表。
- 实现 `UserMemoryService`。
- 在所有 Agent 上下文中注入 User Memory。
- 增加前端管理入口。

验收：

- 用户设置“以后用中文回答”后，所有 Agent 新会话都能遵守。
- 某个 Agent 专属信息不会误写到 User Memory。

### Phase 3：Agent Retrieved Memory

目标：

- 让 Agent Memory 不只靠少量核心记忆，还能按需检索更多长期记忆。

任务：

- 为 `agent_memory` 增加 embedding。
- 复用现有 pgvector 检索能力。
- 根据最新用户输入检索 topK 相关 Agent Memory。
- 增加去重和重要性排序。

验收：

- 某个 Agent 很久以前记录的相关事实，能在新会话按需召回。
- 不相关记忆不会进入 prompt。

### Phase 4：历史会话搜索

目标：

- 支持“上次/之前”的跨会话查询。

任务：

- 新增 `chat_message_embedding` 或 `chat_session_summary`。
- 实现 `SessionHistorySearchService`。
- 先搜索当前 Agent 下历史会话。
- 后续可扩展到用户全局历史。

验收：

- 用户问“上次你给我的那个方案是什么”，Agent 能找回相关历史。
- 不把所有历史消息直接塞进上下文。

### Phase 5：Skill Memory

目标：

- 沉淀可复用任务流程，形成项目亮点。

任务：

- 新增 `agent_skill` 表。
- 支持全局 Skill 和 Agent 专属 Skill。
- 实现 Skill 检索和 prompt 注入。
- 前端支持 Skill 管理。

验收：

- 用户让 Agent 做类似任务时，Agent 能参考已有流程。
- Skill 可以被人工编辑和禁用。

### Phase 6：自动记忆写入和治理

目标：

- 让系统从对话中自动判断哪些内容值得长期保存。

任务：

- 实现 `MemoryWriteService`。
- 增加记忆候选抽取 prompt。
- 增加敏感信息过滤。
- 增加相似记忆合并。
- 增加人工确认模式。

验收：

- 用户明确说“记住”时可以写入正确范围。
- API Key、密码不会被写入。
- 重复记忆会更新，不会无限新增。

## 14. 可落地实现步骤和测试计划

记忆系统不建议一次性完整实现。合理做法是把它拆成多个可以独立上线的小闭环，每个小闭环都能单独验证、单独回滚。

整体实施原则：

- 先做手动记忆，再做自动写入。
- 先做 Agent Memory，再做 User Memory。
- 先做核心记忆注入，再做 embedding 检索。
- 先做当前 Agent 范围，再考虑用户全局范围。
- 每一步都必须验证记忆隔离，避免 Agent A 的记忆进入 Agent B。

### 14.1 第一个小闭环：Agent Memory 手动版

这是最推荐优先实现的 MVP。

目标：

- 让同一个 Agent 下的多个会话可以共享长期记忆。
- 不引入 embedding，不引入自动写入，不引入复杂模型判断。

后端步骤：

1. 新增 `agent_memory` 表。
2. 新增 `AgentMemory` entity / DTO / VO。
3. 新增 `AgentMemoryMapper` 和 MyBatis XML。
4. 新增 `AgentMemoryService`。
5. 新增 Agent Memory CRUD 接口。
6. 在 `JChatMindFactory.create()` 中根据 `agentId` 加载 enabled 的 core memory。
7. 将 Agent Memory 渲染为 prompt 片段，注入 `JChatMind.think()` 的 system prompt。

前端步骤：

1. 在 Agent 详情或编辑页增加“记忆管理”入口。
2. 支持新增、编辑、禁用、删除 Agent Memory。
3. 在聊天页面增加“让当前 Agent 记住”按钮。
4. 开发模式下展示“本轮注入了哪些 Agent Memory”。

测试用例：

1. 创建 Agent A，写入记忆“用户正在准备 Java 面试”。
2. 在 Agent A 下新建会话，询问“我现在主要准备什么？”，应能回答 Java 面试。
3. 创建 Agent B，询问同样问题，不应看到 Agent A 的记忆。
4. 禁用 Agent A 的这条记忆，再新建会话，确认不再注入。
5. 无任何 Agent Memory 时，原有聊天、工具调用、RAG 不受影响。

验收标准：

- 同 Agent 跨会话可用。
- 不同 Agent 之间严格隔离。
- 禁用/删除后立即不生效。
- 不依赖模型自动判断，行为稳定可控。

### 14.2 第二个小闭环：User Memory 手动版

目标：

- 支持所有 Agent 都可参考的用户全局偏好。

后端步骤：

1. 新增 `user_memory` 表。
2. 新增 `UserMemoryService` 和 CRUD 接口。
3. 在 `MemoryContextBuilder` 或 `JChatMindFactory` 中加载 enabled 的 User Memory。
4. 将 User Memory 注入到 Agent prompt 中。

前端步骤：

1. 增加用户记忆管理页面。
2. 支持查看、编辑、禁用、删除 User Memory。
3. 聊天页面可显示本轮使用的 User Memory。

测试用例：

1. 写入 User Memory：“用户偏好中文回答”。
2. 在 Agent A 和 Agent B 新建会话，均应遵守该偏好。
3. 写入 Agent Memory：“Agent A 是 Java 面试官”，确认 Agent B 不会使用。
4. 用户当前输入说“这次请用英文回答”时，应以当前输入为准。

验收标准：

- User Memory 可跨 Agent 生效。
- Agent 专属信息不会误放到 User Memory。
- 当前用户输入优先级高于旧记忆。

### 14.3 第三个小闭环：Agent Retrieved Memory

目标：

- 让 Agent Memory 不只靠少量 core memory，还能按需检索更多长期记忆。

实现前提：

- Phase 1 已稳定。
- embedding 服务可用。
- pgvector 已可复用。

后端步骤：

1. 为 `agent_memory` 增加 `embedding` 字段。
2. 保存 memory 时异步生成 embedding。
3. 查询时根据最新用户输入生成 embedding。
4. 只在当前 `agent_id` 范围内做 topK 检索。
5. 将召回结果作为“相关 Agent 记忆”注入 prompt。

测试用例：

1. 给 Agent A 写入 10 条不同主题记忆。
2. 用户询问其中一个主题，确认只召回相关记忆。
3. Agent B 不能召回 Agent A 的记忆。
4. embedding 服务不可用时，系统降级为只使用 Agent Core Memory。

验收标准：

- 检索结果相关。
- topK 数量受控。
- embedding 异常不影响基础聊天。

### 14.4 第四个小闭环：Session History Search

目标：

- 支持用户问“上次/之前”的历史召回。

建议先做关键词版本，再做语义版本。

关键词版本步骤：

1. 判断用户输入是否包含“上次、之前、历史、那个会话、还记得”等触发词。
2. 从当前 Agent 的历史 `chat_session` 和 `chat_message` 中搜索。
3. 使用 `ILIKE` 或 PostgreSQL 全文检索返回少量片段。
4. 将片段以“相关历史片段”的形式注入 prompt。

语义版本步骤：

1. 新增 `chat_session_summary`。
2. 每个会话结束或消息达到一定数量后生成摘要。
3. 给摘要生成 embedding。
4. 根据用户问题检索相关 session summary。
5. 命中后再取该 session 的关键消息片段。

测试用例：

1. 在同一 Agent 下创建两个历史会话，分别讨论不同主题。
2. 新建第三个会话问“上次工具调用问题怎么解决的？”。
3. 系统应只召回工具调用相关会话。
4. 普通问题不应触发历史搜索。

验收标准：

- 不把全部历史塞入 prompt。
- 只在需要时触发。
- 搜索范围默认限制在当前 Agent。

### 14.5 第五个小闭环：Skill Memory

目标：

- 保存可复用任务流程，形成过程记忆。

后端步骤：

1. 新增 `agent_skill` 表。
2. 支持全局 Skill 和 Agent 专属 Skill。
3. 实现 Skill CRUD。
4. 根据关键词或 embedding 检索相关 Skill。
5. 将 Skill 作为“可参考流程”注入 prompt。

前端步骤：

1. 增加 Skill 管理页面。
2. 支持创建、编辑、禁用 Skill。
3. 展示使用次数和最后使用时间。

测试用例：

1. 创建“写城市报告”Skill。
2. 用户要求写城市报告时，Agent 能参考该流程。
3. 禁用 Skill 后不再注入。
4. Agent 专属 Skill 不会进入其他 Agent。

验收标准：

- Skill 是流程参考，不覆盖用户当前要求。
- Skill 注入数量受控。
- Skill 可人工治理。

### 14.6 第六个小闭环：自动记忆写入

这是风险最高的一步，应放到最后。

目标：

- 让系统自动判断哪些内容值得保存，并写入正确范围。

后端步骤：

1. 新增 `MemoryWriteService`。
2. 增加敏感信息过滤。
3. 增加候选记忆抽取 prompt。
4. 判断 scope：`USER / AGENT / SKILL / NONE`。
5. 检索相似已有记忆，决定新增、更新或忽略。
6. 支持 `pending` 状态，隐式推断的记忆先待确认。

推荐策略：

```text
用户显式说“记住 / 以后 / 下次”
  → 可以直接写入，但仍要做敏感信息过滤

模型自动推断出的记忆
  → 先进入 pending
  → 用户确认后生效
```

测试用例：

1. “以后都用中文回答”应写入 User Memory。
2. “这个 Agent 以后按 Java 面试官方式追问我”应写入 Agent Memory。
3. “我的 API Key 是一段明文密钥”不应写入任何记忆。
4. 重复表达同一个偏好时应更新旧记忆，而不是新增多条。

验收标准：

- 自动写入默认可关闭。
- 敏感信息不会进入长期记忆。
- 记忆不会无限重复增长。
- 用户可以查看、确认、禁用、删除自动生成的记忆。

### 14.7 每阶段通用测试清单

每个阶段上线前都要验证：

- `隔离性`：Agent A 的记忆不会进入 Agent B。
- `可控性`：禁用/删除后不再注入。
- `冲突处理`：用户当前输入优先级高于旧记忆。
- `上下文长度`：注入数量和长度受控。
- `安全性`：敏感信息不会进入长期记忆。
- `回归`：没有记忆时，原有聊天、工具调用、RAG 功能不受影响。

### 14.8 推荐第一版开发范围

第一版只做：

```text
agent_memory 表
+
AgentMemoryService
+
Agent Memory CRUD API
+
Agent Core Memory 注入
+
前端手动“让当前 Agent 记住”
+
调试面板显示本轮注入记忆
```

第一版不做：

```text
User Memory
Agent Retrieved Memory
Session History Search
Skill Memory
自动记忆写入
Workspace Memory
```

这样第一次改造的范围足够小，能稳定验证“同一个 Agent 跨会话记忆”这个最核心价值。

## 15. Prompt 设计建议

### 15.1 记忆使用 Prompt

```text
你可以参考以下记忆辅助回答。

规则：
1. User Memory 是用户全局偏好，适用于所有 Agent。
2. Agent Memory 只适用于当前 Agent。
3. 历史片段只是检索结果，不一定代表当前任务。
4. Skill 是可参考流程，不是必须逐字执行。
5. 如果记忆与用户最新输入冲突，以用户最新输入为准。
6. 不要主动暴露内部记忆，除非用户要求查看。
```

### 15.2 记忆写入 Prompt

```text
请判断本轮对话是否包含值得长期保存的记忆。

请先判断归属范围：
- USER：所有 Agent 都应该知道的用户偏好或背景。
- AGENT：只对当前 Agent 有意义的长期事实、偏好、任务状态或反馈。
- SKILL：可复用的任务流程。
- NONE：不保存。

不要保存：
- 一次性闲聊
- 临时测试
- 密钥、密码、Token
- 未确认猜测
- 大段原始聊天全文

请输出 JSON：
{
  "scope": "USER | AGENT | SKILL | NONE",
  "shouldSave": true,
  "memories": [
    {
      "type": "preference | fact | decision | issue | task | feedback | procedure",
      "title": "...",
      "content": "...",
      "importance": 1,
      "confidence": 0.9
    }
  ]
}
```

## 16. 安全和治理要求

长期记忆必须可治理，否则很容易产生记忆污染。

必须支持：

- 敏感信息过滤。
- 用户可查看记忆。
- 用户可删除或禁用记忆。
- 记忆来源可追溯。
- 记忆更新时间可追踪。
- 每轮注入数量可控。
- Agent Memory 不能串到其他 Agent。
- User Memory 不能保存 Agent 专属上下文。

## 17. 面试表达版本

可以这样描述：

> JChatMind 的产品模型是一个用户可以创建多个 Agent，每个 Agent 下有多个会话。所以我没有简单照搬 Hermes 的文件记忆，而是按归属范围重新设计：Session Memory 解决当前会话连续性，User Memory 保存所有 Agent 通用的用户偏好，Agent Memory 保存某个 Agent 跨会话积累的长期上下文，历史会话搜索负责按需召回旧对话，Skill Memory 负责沉淀可复用流程。这样既避免把所有历史塞进上下文，也避免不同 Agent 之间记忆串扰，更符合网页对话型 Agent 平台的实际场景。

## 18. 推荐优先级

最推荐的落地顺序：

1. `Agent Memory MVP`：先解决一个 Agent 多个会话之间的长期记忆。
2. `User Memory`：再做所有 Agent 共享的用户偏好。
3. `Agent Retrieved Memory`：让 Agent Memory 支持 pgvector 按需检索。
4. `Session History Search`：支持“上次/之前”的历史召回。
5. `Skill Memory`：沉淀过程记忆，形成项目亮点。
6. `Workspace Memory`：等产品有工作空间概念后再做。

最小可行版本：

```text
agent_memory 表
+
AgentMemoryService
+
Agent Core Memory 注入
+
手动“让 Agent 记住”功能
```

完成这个 MVP 后，JChatMind 就能从“每个会话独立记忆”升级为“每个 Agent 能跨会话积累长期记忆”的对话 Agent 平台。
