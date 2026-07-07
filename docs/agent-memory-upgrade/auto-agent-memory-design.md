# Agent 自动记忆设计方案

## 1. 目标

在创建 Agent 时支持用户开启或关闭自动记忆能力。

开启后，系统在后台异步整理最近一段对话，由模型自主判断是否需要写入当前 Agent 的长期记忆，并自主判断记忆类型：

```text
core memory
retrieved memory
```

用户不需要理解内部记忆分类，只需要感知：

```text
创建 Agent 时是否开启 Agent 自动记忆
记忆整理频率是多少
```

第一版中，自动记忆配置只允许在创建 Agent 时指定，不支持在已有 Agent 的普通配置页面中后期开启或关闭。

原因：

```text
如果 Agent 创建时未开启自动记忆，后续再开启会产生历史消息处理边界问题：
- 是否应该回溯处理开启前的历史对话
- 如果回溯，应该从哪条消息开始
- 如果不回溯，用户可能误以为历史对话也会被整理
- job state 的 processed 进度容易和真实语义预期不一致
```

因此第一版先采用更清晰的策略：

```text
创建 Agent 时决定是否开启自动记忆。
开启后，只处理该 Agent 后续会话中满足触发条件的新消息。
```

## 2. 非目标

第一版不做以下能力：

```text
不做 User Memory 自动写入
不做 Skill Memory
不做 Session History Search
不做同步阻塞式记忆写入
不做每轮对话都调用模型生成记忆
不做相似记忆合并
不做 pending 审批流
不做召回质量优化
```

第一版只完成：

```text
Agent 自动记忆开关
按用户消息数量触发异步记忆整理
模型从最近一段对话中提取 0~N 条 Agent Memory
模型自主判断 core / retrieved
写入 agent_memory
复用现有 core 固定注入和 retrieved 召回注入能力
```

## 3. Agent 创建配置

在创建 Agent 时新增：

```text
autoMemoryEnabled: boolean
autoMemoryInterval: int
```

### 3.1 autoMemoryEnabled

含义：

```text
是否开启当前 Agent 的自动记忆能力
```

默认值：

```text
false
```

关闭时：

```text
不会触发自动记忆整理
不会调用模型做记忆提取
不会自动写入 agent_memory
```

开启时：

```text
系统按 autoMemoryInterval 配置异步整理最近对话
```

第一版限制：

```text
autoMemoryEnabled 和 autoMemoryInterval 仅在创建 Agent 时配置。
普通 Agent 编辑页面不提供修改入口。
后端 UpdateAgentRequest 第一版不接收这两个字段，避免后期开启导致历史消息处理语义不清。
```

### 3.2 autoMemoryInterval

含义：

```text
每累计多少条用户消息，触发一次自动记忆整理
```

注意这里是用户消息维度，不是全部 chat_message 数量维度。

例如：

```text
autoMemoryInterval = 10
```

表示当前 session 每新增 10 条 user 消息，触发一次自动记忆整理。

整理时从 DB 查询最近一段对话：

```text
最近 autoMemoryInterval 条 user 消息
+ 这些 user 消息之间关联的 assistant 回复
```

不携带 tool 消息。

原因：

```text
tool 回复内容通常已经被 assistant 回复总结或解释
直接携带 tool 原始结果容易导致上下文过长和噪音过多
```

### 3.3 前端交互

新建 Agent 页面新增：

```text
Agent 自动记忆：开关
```

当用户开启自动记忆后，显示：

```text
记忆整理频率：每 N 条用户消息
```

用户可以自行指定范围。

建议第一版后端校验范围：

```text
最小值：3
最大值：50
默认值：10
```

前端文案建议：

```text
开启后，Agent 会在后台定期整理对话，把长期有用的信息保存为当前 Agent 的记忆，用于后续会话。
```

补充提示文案：

```text
自动记忆只能在创建 Agent 时开启。创建后第一版不支持再打开或关闭。
```

## 4. 后端数据结构

### 4.1 agent 表

新增字段：

```sql
ALTER TABLE agent
ADD COLUMN IF NOT EXISTS auto_memory_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE agent
ADD COLUMN IF NOT EXISTS auto_memory_interval INT NOT NULL DEFAULT 10;
```

同步更新：

```text
Agent entity
AgentDTO
AgentVO
CreateAgentRequest
AgentConverter
```

第一版不更新 `UpdateAgentRequest`，因为自动记忆只允许创建时配置。

### 4.2 agent_memory_job_state 表

新增表：

```sql
CREATE TABLE agent_memory_job_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    agent_id UUID NOT NULL REFERENCES agent(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    processed_user_message_count INT NOT NULL DEFAULT 0,
    last_processed_message_id UUID REFERENCES chat_message(id) ON DELETE SET NULL,
    last_processed_message_created_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (agent_id, session_id)
);
```

用途：

```text
记录当前 Agent + 当前 Session 已经处理到哪里
避免重复处理同一段消息
支持异步任务幂等
```

这里的幂等含义是：

```text
同一段 chat_message 即使异步任务被重复触发，也不会被重复整理并重复写入 memory
```

它不是业务记忆表，而是自动记忆任务的处理进度表。

例如：

```text
autoMemoryInterval = 10
当前 session 已有 10 条 user 消息
agent_memory_job_state.processed_user_message_count = 0
```

此时系统触发一次 AutoMemoryEvent，异步任务处理第 1~10 条 user 消息和相关 assistant 回复。

只有当以下步骤全部成功后，才推进 job state：

```text
模型调用成功
模型输出 JSON 解析成功
合法 memory 写入完成，或模型明确返回空 memories
```

然后更新：

```text
processed_user_message_count = 10
last_processed_message_id = 第 10 条 user 消息 ID
last_processed_message_created_at = 第 10 条 user 消息创建时间
updated_at = 当前时间
```

如果模型调用失败、JSON 解析失败或写入过程失败：

```text
只记录日志
不推进 processed_user_message_count
不更新 last_processed_message_id
不更新 last_processed_message_created_at
```

这样下一次触发时仍会从原位置重试，避免因为一次后台失败导致这批消息永久丢失。

## 5. 触发流程

用户发送消息后的主流程保持不变：

```text
用户发送 prompt
保存 user chat_message
发布 ChatEvent
Agent 正常生成回复
SSE 正常推送给前端
```

自动记忆只在后台异步触发，不阻塞 Agent 与用户之间的对话。

### 5.1 用户消息入库后判断是否需要触发

在 `ChatMessageFacadeServiceImpl.createChatMessage()` 中，用户消息入库后：

```text
1. 查询当前 session 对应的 agent
2. 如果 autoMemoryEnabled = false，直接跳过
3. 如果 autoMemoryEnabled = true，统计当前 session 的 user 消息数量
4. 查询 agent_memory_job_state 中已处理的 user 消息数量
5. 如果未处理 user 消息数量 >= autoMemoryInterval，发布 AutoMemoryEvent
6. 不等待事件处理结果，继续正常发布 ChatEvent
```

伪代码：

```java
if (agent.isAutoMemoryEnabled()) {
    int totalUserMessages = chatMessageMapper.countUserMessages(sessionId);
    int processedUserMessages = memoryJobStateMapper.selectProcessedCount(agentId, sessionId);
    if (totalUserMessages - processedUserMessages >= agent.getAutoMemoryInterval()) {
        publisher.publishEvent(new AutoMemoryEvent(agentId, sessionId));
    }
}

publisher.publishEvent(new ChatEvent(agentId, sessionId, userContent));
```

## 6. 异步整理流程

新增：

```text
AutoMemoryEvent
AutoMemoryEventListener
AutoAgentMemoryService
AgentMemoryJobStateMapper
```

事件监听器：

```java
@Async
@EventListener
public void handle(AutoMemoryEvent event) {
    autoAgentMemoryService.consolidate(event.getAgentId(), event.getSessionId());
}
```

### 6.1 查询待处理消息

异步任务中：

```text
1. 获取 agent 配置
2. 获取 agent_memory_job_state
3. 查询 last_processed_message_created_at 之后的消息
4. 取最近 autoMemoryInterval 条 user 消息
5. 同时取这些 user 消息之后、下一条 user 消息之前的 assistant 回复
6. 排除 tool 消息
```

最终给模型的对话内容类似：

```text
[1] user: ...
[2] assistant: ...
[3] user: ...
[4] assistant: ...
```

### 6.2 调用模型提取记忆

异步任务调用模型，不阻塞用户主对话。

模型任务：

```text
从这一段对话中提取 0 条、1 条或多条当前 Agent 的长期记忆
自主判断每条记忆是 core memory 还是 retrieved memory
```

模型输出严格 JSON。

### 6.3 写入 agent_memory

对于模型返回的每条 memory：

```text
1. 校验 memoryScope 只能是 core / retrieved
2. 校验 memoryType 合法
3. 校验 title/content 非空
4. 敏感信息过滤
5. 同 agent_id + memory_scope + title + content 完全重复则跳过
6. 调用 AgentMemoryFacadeService.createAgentMemory()
```

如果是：

```text
memoryScope = retrieved
```

复用现有逻辑生成 embedding 并写入 `agent_memory.embedding`。

### 6.4 更新 job state

当这一批消息处理完成后，更新：

```text
processed_user_message_count
last_processed_message_id
last_processed_message_created_at
updated_at
```

如果模型调用失败或 JSON 解析失败：

```text
记录日志
不更新 job state
等待下次触发时重试
不影响用户对话
```

## 7. 记忆整理 Prompt

### 7.1 System Prompt

```text
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
```

### 7.2 User Message

```text
【当前 Agent 名称】
{agentName}

【当前 Agent System Prompt】
{systemPrompt}

【待整理对话】
[1] user: ...
[2] assistant: ...
[3] user: ...
[4] assistant: ...
```

## 8. 与现有 Agent Memory 召回链路的关系

当前已有能力：

```text
core memory：每次 JChatMindFactory.create() 时固定加载并注入
retrieved memory：根据最新用户消息 embedding 召回后注入
```

自动记忆只负责写入：

```text
agent_memory
```

写入后如何使用，仍复用现有链路：

```text
用户对话
JChatMindFactory.create()
加载 core memory
召回 retrieved memory
构造 system prompt
模型回答
```

## 9. 第一版质量保护

第一版虽然不做相似记忆合并，但需要做最小质量保护：

```text
autoMemoryEnabled=false 时不触发
异步执行，不阻塞对话
模型输出 JSON 解析失败则丢弃
memoryScope 非 core/retrieved 则丢弃
memoryType 非合法值则丢弃
title/content 为空则丢弃
敏感信息命中则丢弃
同 agent_id + memory_scope + title + content 完全重复则跳过
每次最多写入 5 条
```

## 10. 验收标准

### 开关行为

```text
创建 Agent 时 autoMemoryEnabled=false，则该 Agent 不会触发自动记忆模型调用。
创建 Agent 时 autoMemoryEnabled=true，则达到 autoMemoryInterval 后触发异步记忆整理。
第一版不支持在已有 Agent 的编辑页面后期开启或关闭自动记忆。
```

### 异步行为

```text
用户发送消息后，Agent 回复不等待自动记忆完成。
自动记忆失败不影响正常对话。
```

### 记忆写入

```text
模型可从一段对话中返回 0~5 条记忆。
core memory 写入后后续每轮固定加载。
retrieved memory 写入后生成 embedding，并可在相关问题中被召回。
```

### 幂等行为

```text
同一段消息不会被重复处理。
重复触发 AutoMemoryEvent 不会导致同一批消息重复写入。
```

### 安全行为

```text
密码、API Key、token、密钥、身份证号、银行卡号等敏感信息不会写入长期记忆。
```

## 11. 后续增强方向

第一版完成后，再考虑：

```text
相似记忆合并
已有记忆 update / ignore 判断
pending 审批流
自动记忆调试面板
记忆抽取结果可视化
召回质量优化
记忆评测集
```
