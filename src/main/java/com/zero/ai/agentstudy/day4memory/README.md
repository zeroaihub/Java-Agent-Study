# Day4：Memory —— 让 AI Agent 拥有长期记忆

> 本包（`com.zero.ai.agentstudy.day4memory`）是 Day4 的独立学习空间，不影响 `demo/`、`bot/`、`day3funcall/` 等既有学习代码。
>
> 学习方式：每章采用“五段式”结构：为什么学 / 是什么 / 怎么用 / 用在哪 / 避坑与优化。每完成一章，在 `chatlog/` 下归档本章对话记录。

---

## 教学框架（每章五段式）

| 段落 | 目标 | 输出 |
|------|------|------|
| ① 为什么学（核心价值） | 解释技术解决什么痛点、为何重要 | 建立商业价值判断 |
| ② 是什么（概念与原理） | 给出定义，剖析核心流程 | 建立准确心智模型 |
| ③ 怎么用（实战演练） | Java 优先，Python 辅助 | 从看懂到能写 |
| ④ 用在哪（应用场景） | 结合真实 Agent 项目 | 理解落地场景 |
| ⑤ 避坑与优化（进阶提升） | 常见问题、性能、工程化 | 接近企业级规范 |

---

## 目录

- [第一章：为什么 Agent 必须有 Memory](#第一章为什么-agent-必须有-memory)
- [第二章：Memory 分类](#第二章memory-分类)
- [第三章：Chat Memory 工作原理](#第三章chat-memory-工作原理)
- [第四章：长期 Memory 设计](#第四章长期-memory-设计)
- [第五章：Spring AI 与 LangChain4j 中的 Memory](#第五章spring-ai-与-langchain4j-中的-memory)
- [第六章：Java 实现 Memory Agent](#第六章java-实现-memory-agent)
- [第七章：企业最佳实践](#第七章企业最佳实践)
- [第八章：完成 Memory Agent](#第八章完成-memory-agent)

---

## 学习进度追踪

| 章节 | 主题 | 状态 | 练习 |
|------|------|------|------|
| 第一章 | 为什么 Agent 必须有 Memory | 进行中 | 回答：为什么普通聊天机器人没有真正的长期记忆？ |
| 第二章 | Memory 分类 | 未开始 | 画 Memory 分类图 |
| 第三章 | Chat Memory 工作原理 | 未开始 | 解释为什么聊天越久越贵 |
| 第四章 | 长期 Memory 设计 | 未开始 | 设计企业级 UserProfile |
| 第五章 | 框架中的 Memory | 未开始 | 对比 Spring AI 与 LangChain4j |
| 第六章 | Java 实现 Memory Agent | 未开始 | 完成最近 10 轮、Session、Redis、MySQL、兴趣更新 |
| 第七章 | 企业最佳实践 | 未开始 | 设计 Memory 生命周期 |
| 第八章 | 完成 Memory Agent | 未开始 | 实现能根据长期记忆回答的 Agent |

---

## 对话记录归档

每完成一章，在 `chatlog/` 目录下生成一份 `第X章-对话记录.md`，用于沉淀学习过程和复盘。

---

# 第一章：为什么 Agent 必须有 Memory

## ① 为什么学（核心价值）

普通 LLM API 调用天然没有“连续意识”。每次请求对模型来说都是一次新的计算，模型不会自动知道上一轮用户说过什么。

这会导致 AI 客服、AI 办公助手、AI 学习助手出现严重体验问题：

- 用户刚说过订单号，下一轮 Agent 又问订单号。
- 用户已经说明自己是 Java 工程师，下一轮 Agent 又按零基础 Python 学员来讲。
- 用户长期偏好“回答简洁、给 Java 示例”，Agent 每次都需要重新询问。

Memory 的核心价值是：让无状态的模型请求具备连续上下文能力，让 Agent 从“一问一答工具”升级为“持续服务用户的助手”。

## ② 是什么（概念与原理）

Memory 是 Agent 应用层保存、检索、筛选并注入上下文的机制。

它不是模型权重更新，也不是 LLM 真正在脑子里记住了用户，而是系统在下一次请求前，把必要上下文重新放进 Prompt。

核心流程：

```text
用户输入
   |
   v
识别 userId / sessionId
   |
   v
读取短期对话 + 长期画像
   |
   v
筛选、裁剪、压缩 Memory
   |
   v
组装 Prompt
   |
   v
调用 LLM
   |
   v
保存本轮消息与可沉淀信息
```

HTTP 请求天然无状态。服务端不会因为上一次请求里用户说“我叫张三”，就自动在下一次请求中知道用户是张三。Web 系统通常靠 Cookie、Token、Session、Redis、数据库等机制维持状态。Agent 的 Memory 也是同理。

## ③ 怎么用（实战演练）

Java 极简内存版：

```java
package com.zero.ai.agentstudy.day4memory.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleChatMemory {

    private final int maxMessages;
    private final Map<String, List<String>> memory = new ConcurrentHashMap<>();

    public SimpleChatMemory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void add(String sessionId, String role, String content) {
        List<String> messages = memory.computeIfAbsent(sessionId, key -> new ArrayList<>());
        messages.add(role + ": " + content);
        if (messages.size() > maxMessages) {
            messages.remove(0);
        }
    }

    public List<String> get(String sessionId) {
        return memory.getOrDefault(sessionId, List.of());
    }
}
```

本项目第一章已提供可运行 Demo：

```text
src/main/java/com/zero/ai/agentstudy/day4memory/chapter1
├── Chapter1MemoryController.java
├── Chapter1MemoryService.java
├── SimpleSessionChatMemory.java
├── Chapter1ChatRequest.java
└── Chapter1ChatResponse.java
```

启动 Spring Boot 后，可用下面接口观察“没有 Memory”和“有 Memory”的区别。

无 Memory：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter1/no-memory' \
  -H 'Content-Type: application/json' \
  -d '{"message":"我叫张三，是 Java 工程师"}'

curl -X POST 'http://localhost:8080/api/day4/chapter1/no-memory' \
  -H 'Content-Type: application/json' \
  -d '{"message":"我叫什么名字？我的职业是什么？"}'
```

第二次请求不会携带第一次的上下文，所以模型无法可靠知道你的名字和职业。

有 Memory：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter1/with-memory' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s1","message":"我叫张三，是 Java 工程师"}'

curl -X POST 'http://localhost:8080/api/day4/chapter1/with-memory' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s1","message":"我叫什么名字？我的职业是什么？"}'
```

第二次请求会读取 `sessionId=s1` 的历史消息并一起发送给 LLM，因此模型可以基于上下文回答。

Session 隔离：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter1/with-memory' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s2","message":"我叫什么名字？"}'
```

`s2` 不应该读到 `s1` 的记忆。

Python 极简版：

```python
class SimpleChatMemory:
    def __init__(self, max_messages=10):
        self.max_messages = max_messages
        self.memory = {}

    def add(self, session_id, role, content):
        messages = self.memory.setdefault(session_id, [])
        messages.append({"role": role, "content": content})
        self.memory[session_id] = messages[-self.max_messages:]

    def get(self, session_id):
        return self.memory.get(session_id, [])
```

## ④ 用在哪（应用场景）

AI 客服：

- 短期记住当前工单、订单号、用户刚描述的问题。
- 长期记住用户等级、历史投诉偏好、常用收货地址。

AI 办公助手：

- 短期记住当前会议纪要上下文。
- 长期记住用户写周报的格式偏好。

AI 学习助手：

- 短期记住本章对话。
- 长期记住用户是 Java 工程师、目标是成为 Agent 架构师、偏好 Java 优先。

## ⑤ 避坑与优化（进阶提升）

常见坑：

- 误以为 LLM API 自带长期记忆。
- 把全部聊天记录无脑塞进 Prompt，导致成本和延迟失控。
- 不做 session 隔离，造成 A 用户看到 B 用户信息。
- 把密码、token、身份证等敏感信息长期保存。

优化建议：

- 最近对话用短期 Memory，例如 Redis + TTL。
- 用户画像用长期 Memory，例如 MySQL/PostgreSQL。
- 知识库内容不要放进用户记忆，应该交给 RAG。
- 每次注入 Prompt 前先做筛选，只放对当前问题有帮助的记忆。

本章练习：

```text
为什么普通聊天机器人没有真正的长期记忆？
```

---

# 第二章：Memory 分类

## ① 为什么学（核心价值）

如果把所有信息都叫 Memory，系统设计会很快失控。工程上必须区分：哪些信息只服务当前对话，哪些可以长期保存，哪些是临时推理状态，哪些是语义化知识。

分类能力决定了你后续能否设计出可扩展的 Agent Memory 架构。

## ② 是什么（概念与原理）

Memory 常见分类：

```text
Memory
├── Short-term Memory   短期记忆：最近几轮对话
├── Long-term Memory    长期记忆：用户画像、偏好、目标
├── Working Memory      工作记忆：当前任务临时状态
└── Semantic Memory     语义记忆：抽象知识、事实、经验
```

现实生活类比：

- Short-term Memory：你刚才和同事聊的几句话。
- Long-term Memory：你知道同事叫李雷，负责后端架构。
- Working Memory：你正在排查线上故障时脑中的临时变量和步骤。
- Semantic Memory：你知道“Redis 适合缓存，MySQL 适合事务存储”。

## ③ 怎么用（实战演练）

Java 领域模型雏形：

```java
package com.zero.ai.agentstudy.day4memory.model;

import java.time.Instant;
import java.util.List;

public record MemoryContext(
        String userId,
        String sessionId,
        List<ChatTurn> shortTermMessages,
        UserProfile longTermProfile,
        TaskState workingMemory,
        List<String> semanticMemories
) {
}

public record ChatTurn(String role, String content, Instant createdAt) {
}

public record UserProfile(
        String name,
        String profession,
        List<String> skills,
        List<String> interests,
        List<String> learningGoals,
        List<String> preferences,
        List<String> recentProjects
) {
}

public record TaskState(String currentTask, String currentStep, List<String> pendingActions) {
}
```

Python 辅助理解：

```python
memory_context = {
    "short_term": ["最近 10 轮聊天"],
    "long_term": {"name": "张三", "profession": "Java 工程师"},
    "working": {"task": "学习 Day4 Memory", "step": "第二章"},
    "semantic": ["Memory 不是模型权重更新，而是上下文注入机制"]
}
```

本项目第二章已提供可运行 Demo：

```text
src/main/java/com/zero/ai/agentstudy/day4memory/chapter2
├── Chapter2MemoryController.java
├── Chapter2MemoryService.java
├── MemoryCategory.java
├── MemoryCategoryView.java
├── MemoryClassification.java
├── MemoryClassifyRequest.java
├── MemoryClassifyResponse.java
└── MemoryMapResponse.java
```

接口：

```text
GET  /api/day4/chapter2/map
GET  /api/day4/chapter2/categories
GET  /api/day4/chapter2/examples
POST /api/day4/chapter2/classify
```

测试 Memory 分类图：

```bash
curl 'http://localhost:8080/api/day4/chapter2/map'
```

测试示例分类：

```bash
curl 'http://localhost:8080/api/day4/chapter2/examples'
```

测试自定义分类：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter2/classify' \
  -H 'Content-Type: application/json' \
  -d '{"content":"用户叫张三，是 Java 工程师，目标是成为 AI Agent 架构师。"}'
```

这个接口会判断内容更适合进入 Short-term、Long-term、Working 还是 Semantic Memory，并给出工程存储建议。

## ④ 用在哪（应用场景）

AI 量化交易 Agent：

- Short-term：当前分析的股票、用户刚问的问题。
- Long-term：用户风险偏好、交易周期、禁止投资标的。
- Working：当前回测任务进度、已调用的数据源。
- Semantic：技术指标含义、财报分析规则。

## ⑤ 避坑与优化（进阶提升）

常见坑：

- 把日志当 Memory。
- 把知识库文档塞进用户画像。
- 把临时任务状态永久保存。
- 长期记忆没有置信度和更新时间。

最佳实践：

- 每类 Memory 使用不同存储策略。
- 长期画像字段必须可解释、可更新、可删除。
- Working Memory 通常随任务结束而销毁。
- Semantic Memory 需要和 RAG、向量检索结合。

本章练习：

```text
请画出 Memory 分类图，并用你自己的例子解释四类 Memory。
```

---

# 第三章：Chat Memory 工作原理

## ① 为什么学（核心价值）

Chat Memory 是最常见、最容易上手、也最容易踩坑的 Memory。聊天越久，消息越多，Prompt 越长，token 成本、延迟、错误率都会上升。

理解 Chat Memory，是后续做 Redis 会话缓存、压缩、摘要、滑动窗口的基础。

## ② 是什么（概念与原理）

Chat Memory 保存多轮对话消息，并在下一次请求时注入给 LLM。

消息越来越长的原因：

```text
第 1 轮：System + User1
第 2 轮：System + User1 + Assistant1 + User2
第 3 轮：System + User1 + Assistant1 + User2 + Assistant2 + User3
```

Token 成本越来越高，因为模型每次都需要重新读取上下文。API 不会自动“只计算新增消息”。

控制聊天长度的常见方法：

- 固定窗口：只保留最近 N 条消息。
- 按 token 裁剪：控制最大上下文 token。
- 摘要压缩：把旧对话总结成短摘要。
- 重要信息抽取：把姓名、偏好、目标沉淀为 UserProfile。

## ③ 怎么用（实战演练）

Java 滑动窗口：

```java
package com.zero.ai.agentstudy.day4memory.demo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WindowChatMemory {

    private final int maxTurns;
    private final Map<String, Deque<String>> sessions = new ConcurrentHashMap<>();

    public WindowChatMemory(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public void add(String sessionId, String message) {
        Deque<String> queue = sessions.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        queue.addLast(message);
        while (queue.size() > maxTurns * 2) {
            queue.removeFirst();
        }
    }

    public List<String> getMessages(String sessionId) {
        return List.copyOf(sessions.getOrDefault(sessionId, new ArrayDeque<>()));
    }
}
```

Python 压缩思路：

```python
def compress_old_messages(messages):
    old = messages[:-10]
    recent = messages[-10:]
    summary = "用户主要在学习 AI Agent Memory，偏好 Java 示例。"
    return [{"role": "system", "content": "历史摘要：" + summary}] + recent
```

本项目第三章已提供可运行 Demo：

```text
src/main/java/com/zero/ai/agentstudy/day4memory/chapter3
├── Chapter3MemoryController.java
├── Chapter3MemoryService.java
├── Chapter3ChatRequest.java
├── Chapter3ChatResponse.java
├── MemoryGrowthReport.java
├── MemoryStrategy.java
├── MemoryStrategyView.java
└── SimpleTokenEstimator.java
```

接口：

```text
GET  /api/day4/chapter3/growth-report
GET  /api/day4/chapter3/strategies
POST /api/day4/chapter3/full-history
POST /api/day4/chapter3/message-window
POST /api/day4/chapter3/summary-compression
GET  /api/day4/chapter3/history?sessionId=s1
DELETE /api/day4/chapter3/clear?sessionId=s1
```

查看聊天成本增长规律：

```bash
curl 'http://localhost:8080/api/day4/chapter3/growth-report'
```

观察完整历史策略：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter3/full-history' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s1","message":"我叫张三，正在学习 Memory。"}'
```

观察滑动窗口策略：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter3/message-window' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s2","message":"这是第 1 轮对话。"}'
```

观察摘要压缩策略：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter3/summary-compression' \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"s3","message":"这是用于触发摘要压缩的长会话。"}'
```

响应中的关键字段：

- `fullHistoryMessages`：完整历史消息数。
- `promptMessages`：本次实际会发给 LLM 的消息数。
- `estimatedPromptTokens`：估算 prompt token 数。
- `promptPreview`：本次实际注入上下文预览。

## ④ 用在哪（应用场景）

AI 客服连续沟通：

- 最近 10 轮用于理解“刚才说的那个订单”。
- 老对话压缩成“用户反馈订单 A1001 延迟，诉求是退款”。

AI 办公助手：

- 当前文档修改过程保留最近对话。
- 旧编辑意图压缩成摘要，避免 Prompt 无限膨胀。

## ⑤ 避坑与优化（进阶提升）

常见坑：

- 只按消息数量裁剪，不按 token 裁剪。
- 摘要压缩后丢失关键事实。
- 把 assistant 的错误回答也当成事实长期保存。
- 不区分“完整历史”和“模型上下文”。

优化建议：

- 完整聊天历史可用于审计，Chat Memory 只保存模型需要看的内容。
- 重要信息抽取后存入长期画像。
- 摘要要带时间、范围和置信度。
- 对高价值会话记录 token 使用量和响应耗时。

本章练习：

```text
请解释为什么聊天越久越贵。
```

---

# 第四章：长期 Memory 设计

## ① 为什么学（核心价值）

长期 Memory 是 Agent 个性化能力的核心。没有长期记忆，Agent 永远只能服务当前这一次对话；有了长期记忆，Agent 才能长期理解用户。

企业级产品里，长期 Memory 直接影响留存率、转化率和用户信任。

## ② 是什么（概念与原理）

长期 Memory 通常以用户画像 UserProfile 的形式存在。

适合长期保存的信息：

- 稳定身份：姓名、职业、行业。
- 稳定能力：技能、经验方向。
- 稳定偏好：回答风格、语言、代码栈。
- 长期目标：学习目标、职业目标。
- 最近项目：近期正在推进的工作，但需要定期过期或更新。

不适合长期保存的信息：

- 密码、token、银行卡、身份证等敏感信息。
- 一次性闲聊内容。
- 未经确认的推测。
- 可能伤害用户权益的敏感标签。

## ③ 怎么用（实战演练）

Java UserProfile：

```java
package com.zero.ai.agentstudy.day4memory.model;

import java.time.Instant;
import java.util.List;

public class UserProfile {

    private Long id;
    private String userId;
    private String name;
    private String profession;
    private List<String> skills;
    private List<String> interests;
    private List<String> learningGoals;
    private List<String> preferences;
    private List<String> recentProjects;
    private double confidence;
    private Instant updatedAt;

    public boolean isUsable() {
        return confidence >= 0.7;
    }
}
```

MySQL 表设计：

```sql
CREATE TABLE user_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(64),
    profession VARCHAR(128),
    skills JSON,
    interests JSON,
    learning_goals JSON,
    preferences JSON,
    recent_projects JSON,
    confidence DECIMAL(3,2) NOT NULL DEFAULT 0.80,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

Python 辅助理解：

```python
user_profile = {
    "name": "张三",
    "profession": "Java 工程师",
    "skills": ["Spring Boot", "MySQL"],
    "interests": ["AI Agent", "RAG"],
    "learning_goals": ["成为 AI Agent 架构师"],
    "preferences": ["Java 优先", "需要企业案例"],
    "recent_projects": ["AI 学习助手"]
}
```

本项目第四章已提供可运行 Demo：

```text
src/main/java/com/zero/ai/agentstudy/day4memory/chapter4
├── Chapter4ProfileController.java
├── Chapter4ProfileService.java
├── UserProfile.java
├── UserProfilePatch.java
├── InMemoryUserProfileRepository.java
├── ProfileSchemaResponse.java
├── ProfileFieldRule.java
├── FieldDecisionRequest.java
└── FieldDecisionResponse.java
```

接口：

```text
GET  /api/day4/chapter4/schema
POST /api/day4/chapter4/profile
GET  /api/day4/chapter4/profile?userId=u1
POST /api/day4/chapter4/decide
```

示例：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter4/profile' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","name":"张三","profession":"Java 后端工程师","skills":["Spring Boot"],"learningGoals":["成为 AI Agent 架构师"],"preferences":["Java 优先"],"confidence":0.9}'

curl -X POST 'http://localhost:8080/api/day4/chapter4/decide' \
  -H 'Content-Type: application/json' \
  -d '{"content":"我的 API token 是 abc123"}'
```

## ④ 用在哪（应用场景）

AI 学习助手：

- 记住用户是 Java 工程师。
- 回答时优先给 Spring AI / LangChain4j 示例。
- 后续讲 RAG 时自动结合用户已有 Memory 背景。

AI 客服：

- 记住用户是企业客户。
- 优先展示企业 SLA、专属客服流程。

## ⑤ 避坑与优化（进阶提升）

常见坑：

- 没有用户确认就保存推测信息。
- 画像字段过多，后期无法维护。
- 不支持用户删除或更正记忆。
- 最近项目永不过期，导致回答过时。

最佳实践：

- 长期记忆要有来源、更新时间、置信度。
- 敏感信息默认不保存。
- 重要画像更新最好通过“显式表达 + 多次确认”。
- UserProfile 只保存对未来服务有价值的信息。

本章练习：

```text
请设计一个 UserProfile，并说明哪些字段适合长期保存。
```

---

# 第五章：Spring AI 与 LangChain4j 中的 Memory

## ① 为什么学（核心价值）

自己手写 Memory 有助于理解原理，但企业项目需要可维护、可替换、可扩展的框架抽象。Spring AI 与 LangChain4j 都提供了 Memory 相关能力，重点是理解它们背后的设计思想。

## ② 是什么（概念与原理）

Spring AI 的核心设计：

- `ChatMemory`：对话记忆抽象。
- `ChatMemoryRepository`：底层存储抽象。
- `MessageWindowChatMemory`：窗口式聊天记忆。
- Conversation/Session ID：隔离不同用户或不同会话。

LangChain4j 的核心设计：

- `ChatMemory`：保存消息。
- `MessageWindowChatMemory`：按消息数保留窗口。
- `TokenWindowChatMemory`：按 token 控制窗口。
- Memory ID：区分不同用户或会话。

框架背后的共同思想：

```text
业务代码不直接关心存储细节
   |
   v
通过 ChatMemory 抽象读写消息
   |
   v
通过 conversationId/sessionId 隔离会话
   |
   v
底层可替换为内存、Redis、JDBC、向量库
```

## ③ 怎么用（实战演练）

Spring AI 风格：

```java
package com.zero.ai.agentstudy.day4memory.framework;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

public class SpringAiMemoryExample {

    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(10)
            .build();

    public void remember(String conversationId, String userInput) {
        chatMemory.add(conversationId, new UserMessage(userInput));
    }
}
```

LangChain4j 风格：

```java
package com.zero.ai.agentstudy.day4memory.framework;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.data.message.UserMessage;

public class LangChain4jMemoryExample {

    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .id("user-1001-session-a")
            .maxMessages(10)
            .build();

    public void remember(String text) {
        chatMemory.add(UserMessage.from(text));
    }
}
```

本项目第五章已提供可运行 Demo：

```text
src/main/java/com/zero/ai/agentstudy/day4memory/chapter5
├── Chapter5FrameworkMemoryController.java
├── Chapter5FrameworkMemoryService.java
├── FrameworkChatMemory.java
├── WindowFrameworkChatMemory.java
├── ConversationKey.java
├── FrameworkMessage.java
├── SessionRequest.java
├── SessionMemoryResponse.java
└── FrameworkDesignResponse.java
```

接口：

```text
GET    /api/day4/chapter5/design
POST   /api/day4/chapter5/chat
GET    /api/day4/chapter5/history?userId=u1&sessionId=s1
DELETE /api/day4/chapter5/clear?userId=u1&sessionId=s1
```

示例：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter5/chat' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","sessionId":"s1","message":"我正在学习框架 Memory 抽象"}'
```

## ④ 用在哪（应用场景）

Spring AI 更适合 Spring Boot 企业项目：

- 接入现有 Controller、Service、Repository。
- 使用 Redis、MySQL、Micrometer、日志体系。

LangChain4j 更适合 Java Agent 原型和链式编排：

- 快速组织 ChatModel、Tools、Memory、RAG。
- 阅读开源 Agent 项目时容易理解。

## ⑤ 避坑与优化（进阶提升）

常见坑：

- conversationId 设计太随意，无法区分用户和会话。
- 内存 Memory 用在生产环境，重启就丢。
- 多实例部署时没有共享存储。
- 框架 Memory 和业务用户画像混为一谈。

最佳实践：

- conversationId 建议包含 userId 和 sessionId。
- 生产环境短期 Memory 使用 Redis/JDBC。
- 长期用户画像独立建模，不直接塞进 ChatMemory。
- 对 Memory 读写增加日志和指标。

本章练习：

```text
请说明 ChatMemory、Conversation Memory、Session 管理之间的关系。
```

---

# 第六章：Java 实现 Memory Agent

## ① 为什么学（核心价值）

这一章把理论落到工程实现。你需要能独立完成一个具备短期记忆、会话隔离、Redis 缓存、MySQL 用户画像、自动兴趣更新的 Memory Agent。

这类能力是 AI 客服、AI 学习助手、AI 办公助手的基础设施。

## ② 是什么（概念与原理）

目标架构：

```text
Controller
   |
   v
MemoryAgentService
   |
   +--> RedisChatMemoryStore      最近 10 轮聊天
   |
   +--> UserProfileRepository     MySQL 用户画像
   |
   +--> InterestExtractor         自动抽取兴趣
   |
   +--> PromptBuilder             组装 Prompt
   |
   v
ChatModel
```

数据流：

```text
用户消息
   |
   v
读取 Redis 最近 10 轮
   |
   v
读取 MySQL 用户画像
   |
   v
组装 Prompt 调 LLM
   |
   v
保存 user/assistant 到 Redis
   |
   v
从用户消息中抽取兴趣并更新 MySQL
```

## ③ 怎么用（实战演练）

DTO：

```java
package com.zero.ai.agentstudy.day4memory.dto;

public record MemoryChatRequest(
        String userId,
        String sessionId,
        String message
) {
}

public record MemoryChatResponse(
        String answer,
        String conversationId
) {
}
```

会话 ID：

```java
package com.zero.ai.agentstudy.day4memory.session;

public final class ConversationIds {

    private ConversationIds() {
    }

    public static String of(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }
}
```

Redis Memory Store 伪代码：

```java
package com.zero.ai.agentstudy.day4memory.memory;

import java.util.List;

public interface ChatMemoryStore {

    void append(String conversationId, String role, String content);

    List<String> latest(String conversationId, int limit);
}
```

MySQL 用户画像 Repository 伪代码：

```java
package com.zero.ai.agentstudy.day4memory.profile;

import java.util.Optional;

public interface UserProfileRepository {

    Optional<UserProfile> findByUserId(String userId);

    void save(UserProfile profile);
}
```

自动兴趣更新：

```java
package com.zero.ai.agentstudy.day4memory.profile;

import java.util.ArrayList;
import java.util.List;

public class InterestExtractor {

    public List<String> extract(String message) {
        List<String> interests = new ArrayList<>();
        if (message.contains("RAG") || message.contains("知识库")) {
            interests.add("RAG");
        }
        if (message.contains("Agent") || message.contains("智能体")) {
            interests.add("AI Agent");
        }
        if (message.contains("量化")) {
            interests.add("AI 量化交易");
        }
        return interests;
    }
}
```

Service 主流程：

```java
package com.zero.ai.agentstudy.day4memory.service;

import java.util.List;

public class MemoryAgentService {

    private final ChatMemoryStore chatMemoryStore;
    private final UserProfileRepository userProfileRepository;
    private final InterestExtractor interestExtractor;

    public MemoryAgentService(ChatMemoryStore chatMemoryStore,
                              UserProfileRepository userProfileRepository,
                              InterestExtractor interestExtractor) {
        this.chatMemoryStore = chatMemoryStore;
        this.userProfileRepository = userProfileRepository;
        this.interestExtractor = interestExtractor;
    }

    public String chat(String userId, String sessionId, String message) {
        String conversationId = userId + ":" + sessionId;
        List<String> recentMessages = chatMemoryStore.latest(conversationId, 20);
        UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElse(UserProfile.empty(userId));

        String prompt = buildPrompt(profile, recentMessages, message);
        String answer = callLlm(prompt);

        chatMemoryStore.append(conversationId, "user", message);
        chatMemoryStore.append(conversationId, "assistant", answer);
        updateInterests(profile, message);

        return answer;
    }

    private String buildPrompt(UserProfile profile, List<String> recentMessages, String message) {
        return """
                你是企业级 AI Agent 导师。
                用户画像：%s
                最近对话：%s
                用户当前问题：%s
                请结合用户长期记忆和最近对话回答。
                """.formatted(profile, recentMessages, message);
    }

    private String callLlm(String prompt) {
        return "这里替换为 Spring AI ChatClient 调用结果";
    }

    private void updateInterests(UserProfile profile, String message) {
        List<String> interests = interestExtractor.extract(message);
        profile.addInterests(interests);
        userProfileRepository.save(profile);
    }
}
```

Python 辅助理解：

```python
def chat(user_id, session_id, message):
    conversation_id = f"{user_id}:{session_id}"
    recent = redis.lrange(conversation_id, -20, -1)
    profile = mysql.find_user_profile(user_id)
    prompt = build_prompt(profile, recent, message)
    answer = llm.call(prompt)
    redis.rpush(conversation_id, {"role": "user", "content": message})
    redis.rpush(conversation_id, {"role": "assistant", "content": answer})
    update_interests(profile, message)
    return answer
```

本项目第六章已提供可运行 Demo：

```text
src/main/java/com/zero/ai/agentstudy/day4memory/chapter6
├── Chapter6MemoryAgentController.java
├── Chapter6MemoryAgentService.java
├── ChatMemoryStore.java
├── RedisLikeChatMemoryStore.java
├── UserProfileRepository.java
├── MysqlLikeUserProfileRepository.java
├── ProfileSignalExtractor.java
├── Chapter6UserProfile.java
├── StoredChatMessage.java
├── MemoryChatRequest.java
└── MemoryChatResponse.java
```

接口：

```text
POST   /api/day4/chapter6/chat
GET    /api/day4/chapter6/history?userId=u1&sessionId=s1
DELETE /api/day4/chapter6/clear?userId=u1&sessionId=s1
```

示例：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter6/chat' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","sessionId":"s1","message":"我叫张三，是 Java 后端工程师，目标是成为 AI Agent 架构师，Java 优先。"}'
```

## ④ 用在哪（应用场景）

AI 学习助手最终应做到：

- 记住用户名字。
- 记住用户职业。
- 记住用户学习目标。
- 根据用户长期记忆调整回答风格和代码示例。

AI 客服应做到：

- 当前会话中不重复询问订单号。
- 长期知道用户是企业客户还是个人客户。
- 根据用户偏好选择简洁回答或详细解释。

## ⑤ 避坑与优化（进阶提升）

常见坑：

- Redis 只 append 不裁剪，内存持续上涨。
- 用户画像更新没有去重。
- 自动抽取兴趣过于粗糙，误判严重。
- MySQL 更新没有乐观锁，多请求并发覆盖。

最佳实践：

- Redis 使用 List + TTL + 长度裁剪。
- 用户画像更新使用 merge 策略。
- 重要信息更新需要置信度。
- Controller 层必须校验 userId/sessionId/message。
- 所有 Memory 操作记录 traceId，方便排查串话问题。

本章练习：

```text
请实现最近 10 轮聊天、Session 隔离、Redis 缓存、MySQL 用户画像、自动更新用户兴趣。
```

---

# 第七章：企业最佳实践

## ① 为什么学（核心价值）

Memory Demo 容易写，企业级 Memory 难在治理：成本、隐私、生命周期、一致性、可观测性、和 RAG 的协同。

这一章决定你的 Agent 能否从学习项目走向商业产品。

## ② 是什么（概念与原理）

企业级 Memory 包含四个治理维度：

```text
成本治理：压缩、裁剪、缓存
生命周期：创建、更新、过期、删除
画像策略：抽取、确认、合并、回滚
协同机制：Memory + RAG + Tool
```

Memory 和 RAG 协同：

```text
Memory：用户是谁、偏好什么、刚才聊了什么
RAG：业务知识是什么、文档怎么规定、产品怎么使用
Tool：需要执行什么动作、查询什么实时数据
```

## ③ 怎么用（实战演练）

Prompt 组装策略：

```java
package com.zero.ai.agentstudy.day4memory.prompt;

public class MemoryPromptBuilder {

    public String build(String userProfile,
                        String chatSummary,
                        String recentMessages,
                        String ragContext,
                        String userQuestion) {
        return """
                你是企业级 AI Agent。

                【长期用户画像】
                %s

                【历史对话摘要】
                %s

                【最近对话】
                %s

                【知识库检索结果】
                %s

                【用户当前问题】
                %s

                请优先基于知识库事实回答，并结合用户画像调整表达方式。
                """.formatted(userProfile, chatSummary, recentMessages, ragContext, userQuestion);
    }
}
```

生命周期策略示例：

```text
短期聊天：Redis TTL 24 小时
历史摘要：MySQL 保存 90 天
长期画像：长期保存，但用户可删除
敏感信息：默认不入库
低置信度推测：7 天未确认则丢弃
```

本项目第七章已提供可运行 Demo：

```text
src/main/java/com/zero/ai/agentstudy/day4memory/chapter7
├── Chapter7BestPracticeController.java
├── Chapter7BestPracticeService.java
├── BestPracticeResponse.java
├── MemoryLifecyclePolicy.java
├── MemoryType.java
├── CompressionRequest.java
├── CompressionDecision.java
├── ProfileUpdateRequest.java
├── ProfileUpdateDecision.java
└── RagCoordinationResponse.java
```

接口：

```text
GET  /api/day4/chapter7/overview
GET  /api/day4/chapter7/lifecycle
GET  /api/day4/chapter7/rag-coordination
POST /api/day4/chapter7/compression-decision
POST /api/day4/chapter7/profile-update-decision
```

示例：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter7/compression-decision' \
  -H 'Content-Type: application/json' \
  -d '{"messageCount":30,"estimatedTokens":7000,"containsImportantFacts":true}'
```

## ④ 用在哪（应用场景）

AI 知识库：

- RAG 找制度文档。
- Memory 知道用户是 HR 还是研发。
- Tool 查询审批状态。

AI 量化交易 Agent：

- RAG 读取研报和策略文档。
- Memory 记住用户风险偏好。
- Tool 查询实时行情和回测结果。

## ⑤ 避坑与优化（进阶提升）

常见坑：

- RAG 检索结果和 Memory 冲突时没有优先级。
- 用户画像长期不更新，变成过时信息。
- 压缩摘要没有保留关键约束。
- 没有 Memory 删除能力，不符合隐私要求。

最佳实践：

- 事实优先级：系统规则 > RAG 文档 > Tool 实时结果 > 用户 Memory > 模型常识。
- 用户画像要支持查看、编辑、删除。
- 压缩前后做关键字段校验。
- Memory 更新链路要可审计。
- 对高风险行业加入人工确认。

本章练习：

```text
请设计一个 Memory 生命周期策略，并说明 Memory 和 RAG 如何协同。
```

---

# 第八章：完成 Memory Agent

## ① 为什么学（核心价值）

最终目标是完成一个真正能体现长期记忆价值的 Agent：它不仅能连续聊天，还能基于用户画像调整回答。

这就是商业化 Agent 产品的基本形态。

## ② 是什么（概念与原理）

最终 Agent 能力：

- 记住我的名字。
- 记住我的职业。
- 记住我的学习目标。
- 根据长期记忆调整回答内容。

最终架构：

```text
Web API
   |
   v
MemoryAgentService
   |
   +--> ChatMemory（最近 10 轮）
   +--> UserProfile（长期画像）
   +--> ProfileUpdater（抽取并更新画像）
   +--> PromptBuilder（融合 Memory）
   +--> LLM Client（生成回答）
```

## ③ 怎么用（实战演练）

Controller 设计：

```java
package com.zero.ai.agentstudy.day4memory.controller;

import com.zero.ai.agentstudy.day4memory.dto.MemoryChatRequest;
import com.zero.ai.agentstudy.day4memory.dto.MemoryChatResponse;
import com.zero.ai.agentstudy.day4memory.service.MemoryAgentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/day4/memory")
public class MemoryAgentController {

    private final MemoryAgentService memoryAgentService;

    public MemoryAgentController(MemoryAgentService memoryAgentService) {
        this.memoryAgentService = memoryAgentService;
    }

    @PostMapping("/chat")
    public MemoryChatResponse chat(@RequestBody MemoryChatRequest request) {
        String answer = memoryAgentService.chat(
                request.userId(),
                request.sessionId(),
                request.message()
        );
        return new MemoryChatResponse(answer, request.userId() + ":" + request.sessionId());
    }
}
```

测试对话：

```text
用户：我叫张三，是 Java 后端工程师，目标是成为 AI Agent 架构师。
Agent：已了解，我后续会优先用 Java、Spring Boot、Spring AI 讲解。

用户：Memory Agent 应该怎么设计？
Agent：结合你的 Java 后端背景，建议用 Spring Boot 分层设计：
       Controller -> Service -> Redis ChatMemory -> MySQL UserProfile -> PromptBuilder。
```

Python 辅助理解：

```python
profile = {
    "name": "张三",
    "profession": "Java 后端工程师",
    "learning_goal": "成为 AI Agent 架构师"
}

question = "Memory Agent 应该怎么设计？"
answer_style = "优先使用 Java/Spring Boot 架构视角"
```

本项目第八章已提供可运行 Demo：

```text
src/main/java/com/zero/ai/agentstudy/day4memory/chapter8
├── FinalMemoryAgentController.java
├── FinalMemoryAgentService.java
├── FinalProfileRepository.java
├── FinalChatMemoryStore.java
├── FinalProfileExtractor.java
├── FinalUserProfile.java
├── FinalChatMessage.java
├── FinalMemoryChatRequest.java
└── FinalMemoryChatResponse.java
```

接口：

```text
POST   /api/day4/chapter8/chat
GET    /api/day4/chapter8/inspect?userId=u1&sessionId=s1
DELETE /api/day4/chapter8/clear-session?userId=u1&sessionId=s1
```

示例：

```bash
curl -X POST 'http://localhost:8080/api/day4/chapter8/chat' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","sessionId":"s1","message":"我叫张三，是 Java 后端工程师，目标是成为 AI Agent 架构师，Python 辅助，喜欢企业案例。"}'

curl -X POST 'http://localhost:8080/api/day4/chapter8/chat' \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","sessionId":"s1","message":"你记住了什么？"}'
```

## ④ 用在哪（应用场景）

最终 Memory Agent 可扩展为：

- AI 学习助手：长期跟踪学习路径。
- AI 客服：长期识别客户等级、历史问题、服务偏好。
- AI 办公助手：长期记住用户写作风格、项目背景。
- AI 量化交易 Agent：长期记住风险偏好、策略选择、关注标的。

## ⑤ 避坑与优化（进阶提升）

代码规范检查重点：

- 是否有独立包路径，避免污染前几天学习代码。
- Controller、Service、Memory Store、Repository 是否分层清晰。
- userId/sessionId 是否强制传入并隔离。
- Redis 是否有 TTL 和裁剪。
- MySQL 用户画像是否支持更新时间、置信度、删除。
- Prompt 是否限制 Memory 注入范围。
- 是否有测试覆盖跨 session 隔离。

为后续 RAG 做准备：

- 保持 PromptBuilder 可扩展，预留 `ragContext`。
- UserProfile 不保存业务文档内容。
- 把 Memory 检索和 Knowledge 检索分成两个组件。
- 为每次回答记录 Memory 来源和 RAG 来源。

本章练习：

```text
完成最终 Memory Agent，并提交代码让我按企业级规范点评。
```
