# JChatMind Agent Trace 系统升级方案

## 1. 背景和目标

JChatMind 当前已经具备完整的 Agent Loop：

```text
用户发送消息
  ↓
发布 ChatEvent
  ↓
异步创建 JChatMind
  ↓
think：调用模型决定回答或工具调用
  ↓
execute：执行工具并写回上下文
  ↓
循环直到生成最终回答或达到 MAX_STEPS
```

当前系统会保存 assistant 和 tool 消息，也会输出部分运行日志，但仍然难以回答：

```text
某次 Agent 执行经过了哪些步骤？
每一步调用了哪个模型或工具？
模型为什么进入下一轮循环？
工具接收了什么参数、返回了什么结果？
耗时主要发生在哪个阶段？
执行是正常完成、异常失败，还是达到最大步数后停止？
Agent 当时使用的是哪份配置、工具目录和上下文？
```

因此需要增加独立的 Agent Trace 能力。系统为每次 Agent 运行生成唯一 `traceId`，业务代码在关键生命周期产生结构化 Trace Event，通过专用日志通道输出，由采集器持久化并生成可查询的 Trace 数据。

一句话目标：

> 规范 Agent Trace 日志协议，通过结构化日志采集还原 Agent 行为，并提供独立页面按 traceId 查询完整执行过程。

## 2. 核心方案

### 2.1 总体链路

```text
JChatMind 生命周期埋点
  ↓
AgentTraceRecorder 生成结构化 Trace Event
  ↓
AGENT_TRACE 专用 Logger 输出单行 JSON
  ↓
日志采集器采集、解析和校验
  ↓
Trace Consumer 幂等入库并维护 Run 汇总
  ↓
Trace Query API
  ↓
独立 Agent Trace 页面输入 traceId 查询
```

Agent 主流程只负责描述“发生了什么”，不直接依赖最终存储：

```text
Agent Trace Event：业务事实
结构化日志：事件传输通道
Trace Consumer：事件消费和查询投影
Trace 数据库：查询存储
Trace 页面：行为回放
```

### 2.2 不解析现有普通日志

以下普通日志不能作为 Trace 数据源：

```text
log.info("工具调用结果：{}", result)
log.warn("Max steps reached, stopping agent")
log.error("Error running agent", e)
```

原因：

```text
文本格式容易因代码调整而变化
正则解析不稳定
缺少严格的 traceId、sequenceNo 和事件类型
异常堆栈可能跨多行
无法可靠处理大模型输出中的换行和特殊字符
很难进行协议版本控制
```

正确方式是业务代码显式产生标准事件：

```java
traceRecorder.record(AgentTraceEvent.builder()
        .traceId(traceContext.traceId())
        .eventType(TraceEventType.TOOL_CALL_COMPLETED)
        .stepNo(currentStep)
        .payload(toolResult)
        .build());
```

然后由专用 Logger 输出严格的单行 JSON。

### 2.3 与普通日志的关系

```text
Agent Trace 日志：用于结构化采集、行为回放和统计
普通应用日志：用于服务异常排查
MDC.traceId：用于关联两类数据
```

即使普通日志采集不可用，Trace 协议仍然独立；即使 Trace Event 有缺失，也可以通过同一个 `traceId` 继续查询普通应用日志。

## 3. 产品定位和范围

### 3.1 独立诊断入口

Agent Trace 不属于聊天过程展示。第一版目标交互：

```text
进入独立 Agent Trace 页面
  ↓
输入 traceId
  ↓
查询一次 Agent 运行
  ↓
查看运行摘要、步骤时间线和事件详情
```

现有聊天 SSE 继续负责 `AI_THINKING`、`AI_EXECUTING`、`AI_GENERATED_CONTENT` 和 `AI_DONE`，不增加 Trace Event 推送。

### 3.2 第一版完成

```text
为每次 Agent 运行生成 traceId
traceId 显式进入异步执行链路
定义版本化 Agent Trace JSON 协议
使用独立 AGENT_TRACE Logger 输出 Trace Event
采集并持久化 Run、Step、模型调用和工具调用
处理事件重复、乱序、缺失和超时
提供按 traceId 查询的后端接口
提供独立 Agent Trace 查询页面
实现大字段截断
实现 Trace 数据保留和清理
```

### 3.3 第一版不做

```text
不解析普通文本日志生成 Trace
不在聊天页面实时展示 Trace
不通过现有 SSE 推送 Trace Event
不要求严格零丢失
不做跨服务分布式调用链
不强依赖具体日志平台
不提供 Agent 单步重放
不保存隐藏思维链
```

Agent Trace 第一版定位为诊断和可观测数据，不作为财务、合规等要求严格不可丢失的审计数据。如果未来需要严格审计，应将事件传输通道升级为可靠消息系统或事务 Outbox。

## 4. 设计原则

### 4.1 业务埋点与输出方式解耦

JChatMind 依赖 `AgentTraceRecorder`，而不是直接调用某个日志框架：

```java
public interface AgentTraceRecorder {
    void record(AgentTraceEvent event);
}
```

第一版实现：

```text
LoggingAgentTraceRecorder
  └── 截断 → JSON 序列化 → AGENT_TRACE Logger
```

后续可扩展：

```text
NoOpAgentTraceRecorder
DatabaseAgentTraceRecorder
MessageQueueAgentTraceRecorder
CompositeAgentTraceRecorder
```

切换传输方式时，JChatMind 的生命周期埋点不需要重写。

### 4.2 Trace 上下文显式传递

当前 Agent 通过 `@Async` 的 `ChatEventListener` 执行，线程会发生切换。因此：

```text
traceId 必须在发布 ChatEvent 前生成
traceId 必须作为 ChatEvent 字段显式传递
AgentTraceContext 必须由 JChatMindFactory 注入 JChatMind
MDC 只能用于日志关联，不能作为唯一上下文来源
```

目标链路：

```text
HTTP 请求
  ↓ 生成 traceId
ChatEvent(traceId, agentId, sessionId, userMessageId, userInput)
  ↓
ChatEventListener
  ↓
JChatMindFactory
  ↓
JChatMind(AgentTraceContext)
```

### 4.3 Trace 输出不能阻断 Agent

Recorder 必须 fail-safe：

```text
序列化成功：输出 Trace 日志，继续运行
序列化失败：输出普通 warn 日志，继续运行
Trace Logger 写入失败：不向 Agent 主流程继续抛出
```

### 4.4 一条事件一行 JSON

每个 Trace Event 必须序列化为单行 JSON：

```text
禁止手工拼接 JSON
禁止 pretty print
禁止把异常堆栈作为多行文本直接输出
模型内容中的换行必须经过 JSON 转义
每条日志只包含一个完整 Event
```

### 4.5 顺序不依赖时间戳

异步输出、采集批次和消费重试都可能改变事件到达顺序。因此每条事件必须包含：

```text
eventId：全局唯一，用于消费幂等
traceId：关联同一次 Agent Run
sequenceNo：同一 Trace 内单调递增
timestamp：事件发生时间
```

页面按 `sequenceNo` 排序，而不是只按数据库插入时间或 timestamp 排序。

### 4.6 第一版暂不实现内容脱敏

第一版先完成 Trace 协议、日志采集和行为查询闭环，不实现通用字段脱敏，也不引入 `TraceSanitizer`。

这意味着第一版 Trace 应作为受控的内部调试能力使用，查询入口和原始日志都需要限制访问。通用脱敏规则、工具级敏感字段声明和不同捕获等级放到后续安全增强阶段。

## 5. Trace 标识和上下文

### 5.1 traceId

第一版使用 UUID：

```text
550e8400-e29b-41d4-a716-446655440000
```

特点：

```text
全局唯一
不可像连续编号一样轻易枚举
适合在日志、接口和页面中复制
```

如果后续需要按生成时间自然排序，可以在协议兼容的前提下改用 ULID。

### 5.2 traceId 生成时机

traceId 在接收用户消息、发布 `ChatEvent` 之前生成，并写入：

```text
ChatEvent.traceId
AgentTraceContext.traceId
用户 chat_message.metadata.traceId
对应 assistant / tool message metadata.traceId
普通日志 MDC.traceId
所有 Agent Trace Event.traceId
```

聊天消息创建接口建议返回：

```json
{
  "chatMessageId": "uuid",
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

聊天页面第一版可以不展示 traceId，但接口必须返回或能够通过消息详情获取，否则用户无法进入独立页面查询。

### 5.3 AgentTraceContext

建议结构：

```java
public record AgentTraceContext(
        String traceId,
        String agentId,
        String sessionId,
        String userMessageId,
        AtomicInteger sequence
) {
    public int nextSequence() {
        return sequence.incrementAndGet();
    }
}
```

如果后续同一 Trace 内存在并行工具调用，`sequenceNo` 的分配必须线程安全。

## 6. 结构化日志协议

### 6.1 通用格式

每条 `AGENT_TRACE` 日志使用统一 Envelope：

```json
{
  "schemaVersion": "1.0",
  "category": "agent_trace",
  "timestamp": "2026-07-11T10:20:31.123Z",
  "eventId": "df47d2be-94ad-4ed9-a61a-e14f84618f87",
  "traceId": "550e8400-e29b-41d4-a716-446655440000",
  "sequenceNo": 4,
  "stepNo": 1,
  "eventType": "TOOL_CALL_COMPLETED",
  "eventName": "KnowledgeTool",
  "status": "COMPLETED",
  "agentId": "uuid",
  "sessionId": "uuid",
  "startedAt": "2026-07-11T10:20:30.759Z",
  "completedAt": "2026-07-11T10:20:31.123Z",
  "durationMs": 364,
  "payload": {},
  "error": null,
  "metadata": {}
}
```

字段要求：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `schemaVersion` | 是 | 协议版本，采集端按版本解析 |
| `category` | 是 | 固定为 `agent_trace` |
| `timestamp` | 是 | 事件产生时间，UTC ISO-8601 |
| `eventId` | 是 | 事件唯一 ID，用于幂等 |
| `traceId` | 是 | 一次 Agent Run 的唯一 ID |
| `sequenceNo` | 是 | Trace 内单调递增序号 |
| `stepNo` | 否 | Agent Loop 步骤号 |
| `eventType` | 是 | 标准事件类型 |
| `eventName` | 否 | 模型或工具等可读名称 |
| `status` | 是 | `STARTED / COMPLETED / FAILED` |
| `agentId` | 是 | Agent ID |
| `sessionId` | 是 | 会话 ID |
| `durationMs` | 否 | 完成或失败事件耗时 |
| `payload` | 否 | 经过大小限制后的业务数据 |
| `error` | 否 | 结构化异常摘要 |
| `metadata` | 否 | 扩展字段 |

### 6.2 协议版本

第一版固定：

```text
schemaVersion = 1.0
category = agent_trace
```

演进规则：

```text
新增可选字段：保持 1.x 兼容
删除字段或改变字段语义：升级主版本
采集端遇到未知可选字段应忽略
采集端遇到不支持的主版本应进入死信或错误记录
```

### 6.3 Logger 约定

使用独立 Logger：

```java
private static final Logger traceLog =
        LoggerFactory.getLogger("AGENT_TRACE");
```

日志配置需要保证：

```text
AGENT_TRACE 独立输出
消息体只包含 JSON Event
不附加普通文本前缀到消息体
保留时间戳、级别等外层字段时，采集器能明确提取 message
关闭重复向普通应用日志输出，避免双份采集
```

开发环境可以输出到单独文件，例如：

```text
logs/agent-trace.jsonl
```

容器环境优先输出到标准输出，由统一日志采集器按 logger name 或 category 过滤。

## 7. Trace 事件模型

建议第一版事件类型：

```text
RUN_STARTED
CONTEXT_BUILT
STEP_STARTED
MODEL_CALL_STARTED
MODEL_CALL_COMPLETED
MODEL_CALL_FAILED
MODEL_EMPTY_RESPONSE_RETRY
TOOL_CALL_STARTED
TOOL_CALL_COMPLETED
TOOL_CALL_FAILED
STEP_COMPLETED
RUN_COMPLETED
RUN_FAILED
```

行为顺序示例：

```text
RUN_STARTED
CONTEXT_BUILT
STEP_STARTED                         step=1
MODEL_CALL_STARTED                   step=1
MODEL_CALL_COMPLETED                 step=1
TOOL_CALL_STARTED                    step=1
TOOL_CALL_COMPLETED                  step=1
STEP_COMPLETED                       step=1
STEP_STARTED                         step=2
MODEL_CALL_STARTED                   step=2
MODEL_CALL_COMPLETED                 step=2
STEP_COMPLETED                       step=2
RUN_COMPLETED
```

### 7.1 RUN_STARTED

记录运行时快照，而不是只记录配置 ID：

```json
{
  "agentName": "知识助手",
  "model": "deepseek-chat",
  "chatOptions": {
    "temperature": 0.7,
    "topP": 0.9
  },
  "maxSteps": 20,
  "availableTools": ["KnowledgeTool", "DatabaseTool"],
  "knowledgeBaseIds": ["uuid"]
}
```

### 7.2 CONTEXT_BUILT

默认只记录上下文组成：

```json
{
  "historyMessageCount": 12,
  "systemPromptHash": "sha256:...",
  "agentCoreMemoryIds": ["uuid"],
  "agentRetrievedMemoryIds": ["uuid"],
  "userMemoryIds": ["uuid"],
  "estimatedCharacters": 8200
}
```

### 7.3 MODEL_CALL

`MODEL_CALL_STARTED.payload`：

```text
model
attemptNo
messageCount
toolNames
chatOptions
按捕获等级决定的 prompt 摘要
```

`MODEL_CALL_COMPLETED.payload`：

```text
assistant content
tool calls
finish reason
prompt tokens
completion tokens
DSML tool call 是否经过兼容解析
```

模型空响应触发重试时记录 `MODEL_EMPTY_RESPONSE_RETRY`，包含 `attemptNo` 和 `maxAttempts`。

### 7.4 TOOL_CALL

每个 Tool Call 单独记录，不把一批工具合并为一个事件。

请求：

```json
{
  "toolCallId": "call_xxx",
  "toolName": "KnowledgeTool",
  "arguments": {
    "query": "Agent Trace",
    "kbsId": "uuid"
  }
}
```

响应：

```json
{
  "toolCallId": "call_xxx",
  "toolName": "KnowledgeTool",
  "result": "...",
  "originalSize": 18231,
  "truncated": true
}
```

必须保留 Spring AI 的 `toolCallId`，确保模型发起的调用和工具响应能够对应。

### 7.5 RUN_COMPLETED / RUN_FAILED

运行结束事件记录：

```text
最终状态
结束原因
总步骤数
模型调用次数
工具调用次数
累计 token
总耗时
最终 assistantMessageId
异常类型和异常摘要
```

结束原因：

```text
FINAL_RESPONSE
MAX_STEPS_REACHED
MODEL_ERROR
TOOL_ERROR
INTERNAL_ERROR
```

## 8. 日志采集和消费

### 8.1 采集边界

采集器只接收符合以下条件的记录：

```text
logger = AGENT_TRACE
category = agent_trace
schemaVersion 为支持的版本
```

采集步骤：

```text
读取日志记录
  ↓
提取 message JSON
  ↓
校验必填字段和 schemaVersion
  ↓
发送给 Trace Consumer
  ↓
按 eventId 幂等入库
  ↓
更新 agent_trace 查询投影
```

具体日志采集产品不进入领域协议。无论使用本地采集进程、容器日志采集还是现有统一日志平台，消费者接收到的都应是同一个 JSON Event。

### 8.2 消费幂等

日志采集系统通常提供至少一次传递，事件可能重复。数据库必须保证：

```text
eventId 唯一
(traceId, sequenceNo) 唯一
重复事件不重复累计 token、工具次数和耗时
```

消费者处理同一事件时：

```text
首次到达：保存事件并更新 Run 投影
重复到达：忽略或覆盖相同事件，不重复聚合
```

### 8.3 事件乱序

事件可能乱序到达，例如 `MODEL_CALL_COMPLETED` 先于 `RUN_STARTED` 入库。

消费者不应要求父事件已经存在：

```text
agent_trace_event 可以先幂等落库
agent_trace 使用 upsert 创建占位 Run
RUN_STARTED 到达后补全运行快照
查询时按 sequenceNo 排序
```

### 8.4 事件缺失

日志传输不保证严格零丢失。Trace Run 增加完整性字段：

```text
trace_incomplete
first_sequence_no
last_sequence_no
received_event_count
missing_sequences
```

当发现序号缺口，或 Trace 长期没有结束事件时，页面显示：

```text
该 Trace 数据可能不完整
```

### 8.5 查询延迟

日志从产生到可查询存在短暂延迟。用户立即使用 `traceId` 查询时，接口需要区分：

```text
已采集：返回 Trace
尚未采集：返回 TRACE_PENDING
确认不存在或已过期：返回 404
```

为了区分 `TRACE_PENDING` 和不存在，可以在生成 traceId 时保留一个轻量注册记录，或者由页面在短时间窗口内将 404 视为“可能仍在采集”并允许刷新。该策略需要在实现前确定。

## 9. 查询存储

日志文件不是最终查询数据源。采集后的事件写入查询数据库。

### 9.1 agent_trace

一条记录是 Trace Run 的查询投影：

```sql
CREATE TABLE agent_trace (
    id VARCHAR(36) PRIMARY KEY,
    agent_id UUID,
    session_id UUID,
    user_message_id UUID,
    assistant_message_id UUID,

    status VARCHAR(20) NOT NULL,
    finish_reason VARCHAR(30),

    model_name VARCHAR(100),
    total_steps INT NOT NULL DEFAULT 0,
    total_model_calls INT NOT NULL DEFAULT 0,
    total_tool_calls INT NOT NULL DEFAULT 0,
    prompt_tokens INT,
    completion_tokens INT,

    first_sequence_no INT,
    last_sequence_no INT,
    received_event_count INT NOT NULL DEFAULT 0,
    trace_incomplete BOOLEAN NOT NULL DEFAULT FALSE,

    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_ms BIGINT,

    error_message TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,

    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

不建议对 Trace 表设置会因业务数据删除而级联删除的外键。Trace 是独立诊断数据，Agent 或 Session 删除后的保留行为应由 Trace 保留策略决定。

### 9.2 agent_trace_event

```sql
CREATE TABLE agent_trace_event (
    event_id VARCHAR(36) PRIMARY KEY,
    trace_id VARCHAR(36) NOT NULL,
    schema_version VARCHAR(10) NOT NULL,
    sequence_no INT NOT NULL,
    step_no INT,

    event_type VARCHAR(40) NOT NULL,
    event_name VARCHAR(200),
    status VARCHAR(20) NOT NULL,

    occurred_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_ms BIGINT,

    payload JSONB,
    error JSONB,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,

    ingested_at TIMESTAMP NOT NULL DEFAULT NOW(),

    UNIQUE (trace_id, sequence_no)
);

CREATE INDEX idx_agent_trace_event_trace_sequence
ON agent_trace_event(trace_id, sequence_no);
```

### 9.3 Run 投影规则

消费者根据 Event 更新 `agent_trace`：

```text
任意首个事件
  → upsert Trace，占位状态 RUNNING

RUN_STARTED
  → 补全 agentId、sessionId、模型和 startedAt

MODEL_CALL_COMPLETED
  → totalModelCalls + 1，累加 token

TOOL_CALL_COMPLETED / TOOL_CALL_FAILED
  → totalToolCalls + 1

STEP_COMPLETED
  → 更新 totalSteps

RUN_COMPLETED
  → status = COMPLETED，写入 finishReason 和 completedAt

RUN_FAILED
  → status = FAILED，写入错误摘要和 completedAt
```

投影更新必须和 Event 首次插入处于同一事务，只有插入成功时才累计，防止重复消费导致汇总翻倍。

## 10. 后端模块设计

建议新增：

```text
com.kama.jchatmind.trace
├── emit
│   ├── AgentTraceContext
│   ├── AgentTraceEvent
│   ├── AgentTraceRecorder
│   ├── LoggingAgentTraceRecorder
│   ├── TracePayloadTruncator
│   └── TraceProperties
├── ingest
│   ├── AgentTraceEventConsumer
│   ├── AgentTraceEventValidator
│   └── AgentTraceProjectionService
├── model
│   ├── TraceEventStatus
│   ├── TraceEventType
│   ├── TraceRunStatus
│   └── TraceFinishReason
├── entity
│   ├── AgentTrace
│   └── AgentTraceEventEntity
├── mapper
│   ├── AgentTraceMapper
│   └── AgentTraceEventMapper
└── query
    ├── AgentTraceController
    ├── AgentTraceQueryService
    └── AgentTraceDetailResponse
```

发射端和采集端必须分包，避免把“产生事件”和“消费事件”混成同一个职责。

### 10.1 LoggingAgentTraceRecorder

```java
public final class LoggingAgentTraceRecorder implements AgentTraceRecorder {

    private static final Logger traceLog =
            LoggerFactory.getLogger("AGENT_TRACE");

    private final ObjectMapper objectMapper;
    private final TracePayloadTruncator truncator;

    @Override
    public void record(AgentTraceEvent event) {
        try {
            AgentTraceEvent outputEvent = truncator.truncate(event);
            traceLog.info(objectMapper.writeValueAsString(outputEvent));
        } catch (Exception e) {
            LoggerFactory.getLogger(getClass())
                    .warn("Failed to emit agent trace event: traceId={}, eventType={}",
                            event.traceId(), event.eventType(), e);
        }
    }
}
```

实际实现中应避免在 Recorder 自身的异常日志里再次打印完整 payload。

## 11. 现有代码接入点

### 11.1 ChatEvent 发布入口

```text
生成 traceId
将 traceId 写入用户消息 metadata
将 traceId 和 userMessageId 放入 ChatEvent
将 traceId 返回给调用方
```

注意：此处不要求同步创建完整 `agent_trace`。Run 数据由日志采集投影产生。如果产品要求 traceId 生成后立即可查询，则可以增加轻量 Trace Registration，不在此处同步写所有事件。

### 11.2 ChatEventListener

```text
从 ChatEvent 获取 traceId
设置 MDC.traceId / agentId / sessionId
创建 AgentTraceContext
运行 JChatMind
finally 中清理 MDC
```

### 11.3 JChatMind.run()

```text
记录 RUN_STARTED
每轮记录 STEP_STARTED / STEP_COMPLETED
正常结束记录 RUN_COMPLETED
达到 MAX_STEPS 时记录明确 finishReason
异常路径记录 RUN_FAILED
```

### 11.4 JChatMind.think()

```text
调用前记录 MODEL_CALL_STARTED
响应后记录 MODEL_CALL_COMPLETED
空响应重试记录 MODEL_EMPTY_RESPONSE_RETRY
模型异常记录 MODEL_CALL_FAILED
```

### 11.5 JChatMind.execute()

当前通过 `ToolCallingManager.executeToolCalls()` 批量执行工具。第一版优先在执行前从 `lastChatResponse` 为每个 tool call 输出 `TOOL_CALL_STARTED`，执行后根据 `ToolResponse.toolCallId` 输出完成事件。

```text
lastChatResponse.toolCalls
  ↓ 为每个 call 输出 TOOL_CALL_STARTED
ToolCallingManager.executeToolCalls()
  ↓
ToolResponseMessage.responses
  ↓ 按 toolCallId 输出 TOOL_CALL_COMPLETED
```

如果工具抛出异常导致无法获得部分响应，需要捕获异常并为未完成的调用输出 `TOOL_CALL_FAILED`。实现前必须通过多工具调用集成测试验证 ID 对应关系。

后续也可以引入 `TracingToolCallback` 包装每个 ToolCallback，使工具耗时和异常边界更准确。

## 12. 查询接口

第一版提供：

```http
GET /api/agent-traces/{traceId}
```

响应：

```json
{
  "trace": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "COMPLETED",
    "finishReason": "FINAL_RESPONSE",
    "agentId": "uuid",
    "sessionId": "uuid",
    "totalSteps": 2,
    "totalModelCalls": 2,
    "totalToolCalls": 1,
    "durationMs": 4382,
    "traceIncomplete": false
  },
  "events": [
    {
      "sequenceNo": 1,
      "eventType": "RUN_STARTED",
      "status": "COMPLETED"
    },
    {
      "sequenceNo": 2,
      "stepNo": 1,
      "eventType": "MODEL_CALL_COMPLETED",
      "durationMs": 2105
    }
  ]
}
```

后续可增加：

```http
GET /api/agent-traces?sessionId=&agentId=&status=&page=
GET /api/agent-traces/{traceId}/events
```

## 13. 独立 Trace 页面

建议路由：

```text
/agent-trace
```

页面包含：

### 13.1 查询区域

```text
Trace ID 输入框
查询按钮
手动刷新按钮
```

### 13.2 运行摘要

```text
traceId
运行状态和结束原因
是否完整
Agent 和模型
开始、结束和采集时间
总耗时
步骤数
模型和工具调用次数
token usage
```

### 13.3 行为时间线

```text
Run started
Step 1
  Model call                2.10s
  KnowledgeTool             364ms
Step 2
  Model call                1.82s
Run completed
```

点击事件后展示 Payload、Metadata 和 Error JSON。

页面需要明确处理：

```text
TRACE_PENDING：日志尚未采集完成
RUNNING：Agent 仍在执行或结束事件尚未采集
COMPLETED：执行完成
FAILED：执行失败
INCOMPLETE：事件存在缺口
```

第一版不做实时 SSE，可以手动刷新。后续再评估短周期轮询。

## 14. 访问控制和大小限制

### 14.1 查询权限

不能把“知道 traceId”作为唯一授权条件。查询时至少校验：

```text
当前用户是否拥有对应 chat_session
或当前用户是否具有管理员 / Trace 调试权限
```

如果当前身份体系无法完成校验，第一版页面必须限制为内部调试入口。由于第一版暂不实现内容脱敏，原始 `AGENT_TRACE` 日志也必须限制访问。

### 14.2 大字段限制

```yaml
agent-trace:
  enabled: true
  max-payload-chars: 50000
  max-field-chars: 20000
  retention-days: 30
```

截断后保存：

```text
截断内容
原始字符数
内容 hash
truncated=true
```

第一版不引入 `BASIC / STANDARD / FULL` 捕获等级，所有事件遵循统一字段协议。捕获等级与内容脱敏一起作为后续增强能力设计。

## 15. 可靠性和数据保留

### 15.1 可靠性边界

结构化日志方案能够降低 Agent 主链路开销并解耦存储，但日志不是可靠消息队列，可能因为进程退出、缓冲未刷盘、采集器异常或日志轮转配置错误而丢失。

第一版接受少量 Trace 丢失，前提是：

```text
不影响聊天主流程
页面能够标记不完整 Trace
关键运行错误仍写普通应用日志
监控 Trace 日志采集延迟和失败数量
```

如果未来要求严格不丢事件，升级方向：

```text
事务 Outbox
可靠消息队列
数据库直写 + 异步导出
```

### 15.2 超时 Run 修复

后台任务扫描：

```text
status = RUNNING
updated_at 早于超时阈值
```

将其标记为：

```text
status = FAILED
finishReason = INTERNAL_ERROR
traceIncomplete = true
errorMessage = "Agent process interrupted or trace timed out"
```

### 15.3 数据清理

```text
默认保留 30 天
按 completedAt 或最后 ingestedAt 分页清理
先删除 agent_trace_event，再删除 agent_trace
避免一次大事务删除全部过期数据
```

原始 Trace 日志文件的保留时间可以短于查询数据库，但必须与故障恢复需求匹配。

## 16. 测试计划

### 16.1 协议和发射端测试

```text
每条 Event 是合法单行 JSON
schemaVersion 和必填字段正确
eventId 唯一
sequenceNo 并发下单调且不重复
换行和特殊字符正确转义
超长 payload 正确截断并保存 hash
Recorder 序列化失败不影响 Agent
```

### 16.2 采集消费测试

```text
合法 Event 正常入库
未知主版本进入错误处理
重复 eventId 不重复聚合
同一 traceId + sequenceNo 冲突可识别
乱序事件最终能够正确排序和投影
缺失序号标记 traceIncomplete
RUN_STARTED 晚到时补全占位 Run
```

### 16.3 Agent 集成测试

```text
模型直接返回最终回答
单工具调用后返回答案
一次响应包含多个工具调用
工具执行异常
模型空响应后重试成功
模型调用异常
达到 MAX_STEPS
Trace Logger 故障但 Agent 正常完成
```

验证：

```text
事件顺序和 stepNo 正确
toolCallId 请求响应对应正确
结束原因正确
耗时非负
采集后的 Run 汇总不重复累计
```

### 16.4 API 和页面测试

```text
合法 traceId 查询成功
采集延迟时正确展示 pending
不存在的 traceId 正确处理
无权限用户无法查询
RUNNING / COMPLETED / FAILED / INCOMPLETE 正确展示
超长 JSON 可折叠且不会阻塞页面
```

## 17. 分阶段实施计划

### Phase 1：定义 Trace 协议和发射端

```text
定义 AgentTraceEvent JSON Schema
实现 AgentTraceContext 和 sequenceNo
实现 AgentTraceRecorder
实现 LoggingAgentTraceRecorder
实现 payload 截断
配置 AGENT_TRACE 独立 Logger
```

验收标准：

```text
本地可以在 agent-trace.jsonl 中看到合法单行 JSON
Recorder 异常不影响 Agent
```

### Phase 2：接入 Agent Loop

```text
入口生成 traceId
扩展 ChatEvent
异步线程设置 MDC
run() 记录 Run 和 Step
think() 记录模型调用
execute() 记录逐个工具调用
```

验收标准：

```text
仅查看 JSONL 即可按 traceId 还原一次完整执行
模型和工具请求响应关联正确
失败和 MAX_STEPS 有明确结束原因
```

### Phase 3：日志采集和查询投影

```text
配置采集规则
实现协议校验和消费
新增 agent_trace / agent_trace_event
实现幂等、乱序和缺失处理
实现超时 Run 修复
```

验收标准：

```text
结构化日志可以稳定进入查询数据库
重复采集不导致汇总翻倍
乱序和缺失可以识别
```

### Phase 4：查询 API

```text
实现按 traceId 查询详情
增加访问权限校验
聊天消息创建响应返回 traceId
处理 pending / incomplete 状态
```

### Phase 5：独立查询页面

```text
新增 /agent-trace 路由
实现 traceId 查询表单
实现运行摘要和步骤时间线
实现 JSON 详情查看器
实现手动刷新和异常状态展示
```

### Phase 6：运行治理

```text
清理过期 Trace 和原始日志
监控采集延迟、解析失败和缺失率
评估采样率
评估通用脱敏和不同捕获等级
评估可靠消息通道和外部可观测平台导出
```

## 18. 待 Review 决策

1. 当前部署环境是否已经存在统一日志采集系统；如果没有，第一版采集器采用什么运行方式。
2. Trace 日志输出到独立 JSONL 文件还是容器标准输出。
3. traceId 是否在聊天发送接口直接返回，还是只写入消息 metadata。
4. 第一版 Trace 页面面向会话所有者，还是仅面向管理员和开发者。
5. 第一版是否保存模型最终输出和工具完整结果，还是只保存摘要和大小信息。
6. Trace 数据和原始 Trace 日志分别保留多久。
7. 达到 `MAX_STEPS` 时最终状态定义为 `COMPLETED` 还是 `FAILED`。
8. Agent Trace 默认全量开启，还是通过配置和采样率控制。
9. 对于 traceId 已生成但日志尚未采集的情况，采用轻量 Registration 还是页面短暂重试。
10. 是否预计近期支持并行工具或多 Agent；如果是，应在第一版增加 `spanId / parentSpanId`。
11. Trace 的完整性要求是否允许少量丢失；如果不允许，需要改用可靠消息或 Outbox。

## 19. 后续演进

```text
按 Agent、Session、状态和时间范围搜索
工具失败率和模型耗时统计
token 与成本统计
Trace 对比
基于 Trace 的 Agent 回归测试集
多 Agent 父子 Trace
对接 OpenTelemetry / Jaeger
对接 Langfuse 等 LLM 可观测平台
事务 Outbox 或消息队列
受控的离线重放能力
```

外部平台应作为 Trace Event 的消费方或导出目标，而不是替代 JChatMind 自己的业务协议。JChatMind 仍需稳定保留：

```text
traceId
eventId
sequenceNo
agentId
sessionId
messageId
stepNo
toolCallId
finishReason
```

这些用于准确还原 Agent 行为的业务字段。
