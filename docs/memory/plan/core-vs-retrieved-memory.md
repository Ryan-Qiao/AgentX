# Agent Memory 分类标准：Core Memory 与 Retrieved Memory

## 1. 背景

JChatMind 的 Agent Memory 属于某个具体 Agent，用于支持同一个 Agent 在多个会话之间保持长期上下文。

当前 Agent Memory 分为两类：

```text
core memory
retrieved memory
```

二者都属于 Agent 层级的 memory，存储在 `agent_memory` 表中。

## 2. 总体定义

```text
Core Memory = Agent 稳定身份、长期职责、固定风格、硬性边界、强约定
Retrieved Memory = Agent 历史经验、项目上下文、主题事实、讨论结论、低频背景
```

更简单地说：

```text
core memory：决定这个 Agent 长期稳定“是谁、怎么工作、边界是什么”
retrieved memory：这个 Agent 积累的可检索经验、事实、上下文，只在相关问题里使用
```

## 3. Core Memory

Core Memory 是少量、稳定、强约束、每轮都值得注入的内容。

它不依赖召回，因为它一旦缺失，就可能影响 Agent 的身份、边界或基本行为。

### 3.1 适合放入 Core Memory 的内容

#### Agent 身份

```text
当前 Agent 是 MySQL 面试官。
当前 Agent 是论文润色助手。
当前 Agent 是 Java 后端架构评审助手。
```

#### Agent 长期任务边界

```text
这个 Agent 只负责 MySQL 面试训练，不回答无关闲聊。
这个 Agent 主要帮助用户做后端项目简历优化。
```

#### Agent 固定工作风格

```text
回答前先指出问题核心，再给分步骤建议。
遇到面试题时先追问用户思路，再给参考答案。
```

#### Agent 长期不可变约束

```text
不要直接代替用户完成面试回答，要以引导方式帮助用户组织答案。
不要编造知识库中没有的信息。
```

#### Agent 与用户在该 Agent 内的长期稳定约定

```text
在这个 Agent 中，默认把“项目”理解为 JChatMind。
在这个 Agent 中，默认使用中文交流，除非用户明确要求英文。
```

### 3.2 Core Memory 的特征

```text
数量少
稳定
高优先级
每轮注入
不需要召回判断
通常影响 Agent 的身份、边界或行为方式
```

### 3.3 不适合放入 Core Memory 的内容

以下内容不应固定注入：

```text
某一次具体讨论细节
某个项目里的单个技术点
一次临时决策
一段用户上传文档的摘要
一条只和特定问题相关的经验
```

原因是这些内容只在特定问题下有价值，每轮都塞进 prompt 会污染上下文。

例如：

```text
用户之前问过 B+Tree 为什么比 Hash 更适合范围查询。
```

这类内容更适合放入 Retrieved Memory。

## 4. Retrieved Memory

Retrieved Memory 是数量可以较多、主题分散、不一定每轮都需要、只在和当前问题相关时才注入的内容。

它适合通过 embedding、rerank、过滤等召回链路按需使用。

### 4.1 适合放入 Retrieved Memory 的内容

#### 某个主题的历史经验

```text
用户之前在 MySQL 索引问题上容易混淆回表和覆盖索引。
用户曾经要求 explain 分析时重点关注 type、key、rows、Extra。
```

#### 某个项目或业务上下文

```text
JChatMind 的 RAG 模块使用 PostgreSQL + pgvector 存储 chunk embedding。
JChatMind 当前的 KnowledgeTool 只能访问 Agent 绑定的知识库。
```

#### 某次讨论沉淀出的结论

```text
关于 RAG 简历亮点，最终采用“两阶段检索 + Hybrid Rerank + 离线评测指标”的表达。
```

#### 可复用但不属于流程的事实

```text
用户上传过《图解 MySQL》PDF，并用它验证过 B+Tree 检索效果。
```

#### 低频但未来可能有用的背景

```text
用户曾经关注过 Docker 云端部署，但暂时没有实施。
用户希望未来优化 Agent trace，用于追踪 Agent 行为。
```

### 4.2 Retrieved Memory 的特征

```text
数量多
主题分散
不每轮注入
通过 embedding / rerank / filter 召回
只在当前问题相关时使用
通常补充 Agent 对过去上下文的记忆
```

### 4.3 不适合放入 Retrieved Memory 的内容

以下内容不应依赖召回：

```text
Agent 是谁
Agent 的固定角色
Agent 的永久任务边界
Agent 必须遵守的长期行为规则
```

原因是这些内容不能靠召回命中来决定是否生效。

例如：

```text
当前 Agent 是 MySQL 面试官。
```

这类内容必须每轮稳定生效，所以应该放入 Core Memory。

## 5. 判断规则

判断一条 Agent Memory 应该进入 Core Memory 还是 Retrieved Memory，可以先问两个问题。

### 5.1 问题一：如果这条记忆本轮没有被召回，会不会影响 Agent 的身份、边界或基本行为？

如果会：

```text
core memory
```

如果不会，只是在相关场景下有帮助：

```text
retrieved memory
```

### 5.2 问题二：这条记忆是不是只对某类问题有用？

如果是：

```text
retrieved memory
```

如果几乎每轮都应该影响 Agent：

```text
core memory
```

## 6. 与其他记忆类型的边界

### User Memory

用户全局偏好、用户稳定背景、所有 Agent 都应该知道的沟通习惯，应进入 User Memory。

例如：

```text
用户偏好中文回答。
用户喜欢先给结论。
用户正在准备 Java 后端面试。
```

### Skill Memory

流程、步骤、SOP、方法论、可复用任务模板，应进入后续 Skill Memory。

例如：

```text
写城市报告时，先列提纲，再补充数据，最后总结风险。
做代码 review 时，先看正确性，再看边界条件，最后看可维护性。
```

## 7. 最终分类结论

```text
身份 / 边界 / 长期行为规则 / 强约定 -> core memory
经验 / 事实 / 项目上下文 / 讨论结论 / 低频背景 -> retrieved memory
流程 / 步骤 / SOP / 方法论 -> skill memory
用户全局偏好 / 用户稳定背景 -> user memory
```

这一分类标准应作为后续自动写入、召回过滤、prompt 注入和前端管理设计的基础。
