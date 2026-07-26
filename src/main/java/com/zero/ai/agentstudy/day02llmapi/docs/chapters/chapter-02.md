# 第二章：Chat Completion 详细原理

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day02 · 第二章
>
> 五段式教学模板：**为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化**。
> 本章讲清一次 AI 请求从发出到返回的完整机制。学完请回答章末问题：
> **「一次 AI 请求到底经历了什么？」**

---

## 第一部分：为什么学（核心价值）

### 1. 为什么要专门学 Chat Completion？

第一章我们知道了「AI 应用 = 调 LLM API」。但 API 有很多形态，
今天几乎所有主流大模型对外提供的核心能力，都是同一种范式——**Chat Completion（对话补全）**。

> 你调 OpenAI 也好、通义 / DeepSeek / 智谱 / Kimi 也好，本地 LM Studio / Ollama 也好，
> 只要它们「兼容 OpenAI 协议」，那么请求体、响应体、字段含义**几乎完全一致**。

这意味着：**你把 Chat Completion 这一个协议吃透，就等于掌握了 90% 的模型调用能力。**
换厂商时，你改的只是 `base-url`、`api-key`、`model` 三个值，业务代码一行不用动。
这正是本训练营强调「面向协议编程」的根本原因。

### 2. 不懂原理会踩什么坑？

很多人以为「调 API 就是发个字符串、拿个字符串」，结果上线后满是问题：

- 不知道 `messages` 是数组，把多轮对话写成拼接字符串，模型「失忆」。
- 不知道 `max_tokens` 会截断，回答到一半没了，还以为模型坏了。
- 不知道 `temperature` 的含义，客服机器人被调成「胡说八道」模式。
- 不知道 `usage` 是账单，上线一周烧掉几千块还蒙在鼓里。
- 不知道模型是「无状态」的，以为服务端帮你记住了上一句。

**懂原理，才知道每个参数在替你做什么决定、花多少钱、有什么副作用。**

### 3. 从「文本补全」到「对话补全」的演进

早期的 LLM API 是 **Text Completion（文本补全）**：你给一段文本，模型接着往下写。
它的问题是**没有角色概念**，无法优雅地表达「谁说了什么」，多轮对话与人设都得靠字符串硬拼，脆弱且难维护。

于是演进出 **Chat Completion（对话补全）**：把输入结构化成一个 **消息数组（messages）**，
每条消息带 `role`（角色）和 `content`（内容）。这样就能清晰表达：

```
系统给的人设（system）+ 用户说的话（user）+ 之前 AI 的回答（assistant）
```

**结论：Chat Completion 用「结构化消息数组」取代「裸字符串」，这是现代 LLM 应用的基石。**

---

## 第二部分：是什么（概念与原理）

### 2.1 请求体结构详解

一个标准的 Chat Completion 请求：

```json
{
  "model": "gpt-4o-mini",
  "messages": [
    { "role": "system",    "content": "你是一位资深 Java 架构师，回答专业、简洁。" },
    { "role": "user",      "content": "什么是 Spring 的 IOC？" },
    { "role": "assistant", "content": "IOC 即控制反转……" },
    { "role": "user",      "content": "那 DI 和它什么关系？" }
  ],
  "temperature": 0.7,
  "max_tokens": 1024,
  "top_p": 1.0,
  "frequency_penalty": 0,
  "presence_penalty": 0,
  "stream": false,
  "stop": ["###"]
}
```

逐字段拆解：

#### model—— 选哪个模型

指定用哪个模型来推理。不同模型能力、速度、价格差异巨大：

- 强模型（如 gpt-4 系列）：智能高、贵、慢，适合复杂推理。
- 轻模型（如 mini / 小参数模型）：便宜、快，适合简单问答、分类、摘要。

**企业实践：按任务分级选模型（Model Routing），简单任务用轻模型省钱。**

#### messages —— 对话上下文数组（最核心）

这是**整个请求的灵魂**。它是一个**有序数组**，每个元素是一条消息：

```json
{ "role": "system | user | assistant", "content": "文本内容" }
```

- `role` 表示这句话是「谁说的」：
  - `system`：系统设定（人设、规则、边界），优先级最高。
  - `user`：用户输入。
  - `assistant`：AI 之前的回答（用于多轮记忆）。
- **顺序即时间线**：数组顺序就是对话发生的先后顺序。
- **模型本身无状态**：模型不记得你上次说了什么，**每次请求你都要把完整历史带上**。
  （第三章会专门讲 Message 体系，第五章会用代码实现多轮记忆。）

#### temperature —— 随机性（0 ~ 2）

控制输出的「发散程度 / 创造性」：

- `0`：几乎确定性，同样输入基本同样输出。适合**客服、抽取、分类、代码**。
- `0.7`：默认值，兼顾稳定与灵活。适合通用问答。
- `1.5 ~ 2`：高度发散、天马行空。适合**创意写作、头脑风暴**。

> 原理：模型每一步都在预测「下一个 Token 的概率分布」。temperature 越高，
> 越会从低概率的候选里采样，输出越「意外」；越低越只选最高概率，输出越「稳」。

#### max_tokens —— 最多生成多少 Token

限制**输出（completion）**的最大 Token 数（不含输入）。作用：

- **控成本**：Token 越多越贵，设上限防止失控。
- **防失控**：避免模型无限啰嗦。

**注意**：设太小会被**截断**（`finish_reason = length`），回答戛然而止。要按业务合理设置。

#### top_p —— 核采样（0 ~ 1）

另一种控制随机性的方式：只从「累计概率达到 top_p」的候选 Token 里采样。

- `top_p = 1`：考虑全部候选。
- `top_p = 0.1`：只从概率最高的一小撮里选，更稳。

**实践建议：temperature 和 top_p 二选一调，不要同时大改，否则难以预期。**

#### frequency_penalty / presence_penalty —— 抑制重复

- `frequency_penalty`（频率惩罚）：出现越多次的词，越降低它再次出现的概率 → 减少「复读」。
- `presence_penalty`（存在惩罚）：只要出现过就惩罚 → 鼓励谈新话题。
- 取值一般 `-2.0 ~ 2.0`，默认 `0`。长文本生成防啰嗦时可小幅调高。

#### stream —— 是否流式返回

- `false`：一次性返回完整 JSON（本章重点）。
- `true`：SSE 逐块推送（第四章专讲）。

#### stop —— 停止词

遇到指定字符串就停止生成。用于自定义边界，如 `"###"`、`"\n\n"`。

### 2.2 响应体结构详解（非流式）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1770000000,
  "model": "gpt-4o-mini",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "IOC（Inversion of Control，控制反转）是一种设计思想……"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 42,
    "completion_tokens": 156,
    "total_tokens": 198
  }
}
```

逐字段拆解：

- `id`：本次请求的唯一标识，排查问题 / 对账时用。
- `model`：实际使用的模型（有时厂商会做版本映射）。
- `choices`：候选回答数组（默认 1 个）。
  - `message.content`：**AI 的回答正文**，你要取的就是它。
  - `message.role`：固定是 `assistant`。
  - `finish_reason`：**为什么停下**，非常重要：
    - `stop`：正常结束（模型自己说完了 / 命中 stop 词）。
    - `length`：**被 max_tokens 截断**，回答不完整，需要加大上限或续写。
    - `content_filter`：被内容安全策略拦截。
    - `tool_calls`：模型要求调用工具（Function Calling，Day3 讲）。
- `usage`：**本次 Token 消耗（=账单）**：
  - `prompt_tokens`：输入消耗（你发过去的 messages）。
  - `completion_tokens`：输出消耗（模型生成的回答）。
  - `total_tokens`：总消耗 = 前两者之和，**计费依据**。

### 2.3 一次请求的完整生命周期

把第一章的链路 + 本章的字段，串成一条完整时间线：

```
① Java 应用组装请求
   选 model、拼 messages、设 temperature/max_tokens
        ↓ HTTPS POST（带 Authorization: Bearer <API_KEY>）
② API Gateway
   鉴权（校验 Key）→ 限流（RPM/TPM）→ 路由到模型集群
        ↓
③ Model Service
   把 messages 编码成 Token 序列（输入 Tokenization）
   排队、组 batch
        ↓
④ 模型推理（GPU）
   自回归逐 Token 生成：预测下一个 Token → 采样(受 temperature/top_p 影响) → 追加 → 再预测……
   直到遇到结束符 / 命中 stop / 达到 max_tokens
        ↓
⑤ 组装响应
   把生成的 Token 解码成文本 → 填 content
   统计 usage → 填 finish_reason
        ↓ HTTP 200 + JSON
⑥ Java 应用处理响应
   取 choices[0].message.content 给业务
   记录 usage 做成本统计，检查 finish_reason
```

> **关键认知：模型是"自回归逐 Token 生成"的**——它不是一下算出整段话，
> 而是一个 Token 一个 Token 地往外蹦（这也正是第四章 Streaming 能成立的物理基础）。

---

## 第三部分：怎么用（实战）

本章聚焦「原理理解 + 请求/响应观察」。用最直观的 `curl` 亲手发一次请求，看清全过程。

### 3.1 用 curl 发一次非流式请求

以 OpenAI 兼容协议为例（本项目本地用 LM Studio，`base-url=http://127.0.0.1:1234`）：

```bash
curl http://127.0.0.1:1234/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-1234" \
  -d '{
    "model": "huihui-qwen3.5-9b-abliterated-mlx",
    "messages": [
      { "role": "system", "content": "你是一位资深 Java 架构师，回答专业、简洁。" },
      { "role": "user",   "content": "用一句话解释什么是 IOC？" }
    ],
    "temperature": 0.7,
    "max_tokens": 256,
    "stream": false
  }'
```

你会拿到一个完整 JSON。**重点观察三处**：

1. `choices[0].message.content` —— 回答对不对。
2. `choices[0].finish_reason` —— 是 `stop`（完整）还是 `length`（被截断）。
3. `usage.total_tokens` —— 这次花了多少 Token。

### 3.2 做几组对照实验（强烈建议动手）

改动**单个参数**，观察输出变化，建立直觉：

实验 A：把 `temperature` 从 `0` 调到 `1.8`，问同一个开放性问题（如「给我起 3 个咖啡店名字」），
观察输出从「保守稳定」变得「天马行空」。

实验 B：把 `max_tokens` 设成 `20`，问一个需要长回答的问题（如「详细讲讲什么是 Spring 事务传播机制」），
观察 `finish_reason` 变成 `length`，且回答被硬生生截断。

实验 C：`messages` 里**只放当前问题**（不带历史）问「那它和 DI 什么关系？」，
观察模型「听不懂"它"指什么」——**亲身验证模型是无状态的**，历史必须由你带上。

### 3.3 对应到 Spring AI 的写法（预告第五章）

上面 curl 里的每个字段，第五章都会用 Spring AI 优雅地表达：

```java
String answer = chatClient.prompt()
        .system("你是一位资深 Java 架构师，回答专业、简洁。")  // system message
        .user("用一句话解释什么是 IOC？")                      // user message
        .options(OpenAiChatOptions.builder()
                .temperature(0.7)                             // temperature
                .maxTokens(256)                               // max_tokens
                .build())
        .call()                                               // stream=false，一次性返回
        .content();                                           // 取 choices[0].message.content
```

**你会发现：Spring AI 只是把 Chat Completion 协议包了一层 Java 友好的 API，本质一模一样。**

---

## 第四部分：用在哪（真实场景）

理解 Chat Completion 机制后，看它如何支撑真实产品的关键决策：

1. **AI 客服**：`temperature=0` 保证回答稳定不乱说；`system` 固定客服人设与话术边界。
2. **AI 创意写作**：`temperature=1.2~1.8` 让文案更有灵气；`presence_penalty` 调高换新角度。
3. **AI 摘要/抽取**：`temperature=0` + 明确 `system` 指令，保证结构化、可解析。
4. **AI 编程助手**：低温 + 大 `max_tokens`（代码可能很长），并检查 `finish_reason=length` 触发续写。
5. **AI 知识库问答（RAG）**：把检索片段拼进 `messages`，低温保证「只依据资料回答」。
6. **AI 多轮咨询**：把历史 `user/assistant` 交替放进 `messages`，实现上下文记忆。
7. **AI 分类/路由**：低温 + 小 `max_tokens` + `stop` 词，让模型只吐一个标签，省钱又快。
8. **AI 教育出题**：中温平衡「题目多样」与「答案严谨」，`system` 约束题型与难度。
9. **AI 营销文案批量生成**：适度高温产生多样文案，用 `usage` 统计每条成本做 ROI 核算。
10. **AI Agent 决策**：低温保证决策稳定，`finish_reason=tool_calls` 触发工具调用（Day3 展开）。

**共性规律**：确定性任务用低温，创意任务用高温；输出长的任务加大 max_tokens 并防截断；
所有任务都用 system 立规矩、用 usage 算成本。

---

## 第五部分：避坑与优化（企业最佳实践）

围绕 Chat Completion，10 条企业级红线：

1. **别把多轮拼成一个字符串**：必须用 `messages` 数组表达角色与顺序，否则模型混乱、人设失效。
2. **永远带完整（或裁剪后的）历史**：模型无状态，服务端不带历史 = 模型失忆。
3. **max_tokens 要留余量**：按业务预估输出长度并留 buffer，避免频繁 `length` 截断。
4. **检查 finish_reason**：不是 `stop` 就要处理（截断续写、内容拦截提示、工具调用分支）。
5. **temperature 按任务设，不要一刀切 0.7**：客服/抽取用 0，创意用高温，别让参数拖垮体验。
6. **usage 必须记录**：每次调用记 `total_tokens`，做成本看板与异常告警，防「悄悄烧钱」。
7. **控制历史长度（上下文窗口有限）**：历史越长 `prompt_tokens` 越贵，且可能超出上下文上限。
   实践：只保留最近 N 轮、或对旧历史做摘要压缩（第六章展开）。
8. **temperature 与 top_p 不要同时乱调**：二选一，否则输出不可预期、难以复现问题。
9. **面向协议而非厂商**：字段用 OpenAI 兼容协议标准写法，换模型只改配置不改代码。
10. **可复现性**：排查线上问题时，把 `temperature` 临时设 `0` 更容易复现（降低随机性干扰）。

**本章最佳实践总结**：Chat Completion 的每个参数都在替你做「质量 / 成本 / 稳定性」的权衡。
懂了它们，你才能像调数据库连接池一样，精细地「调」你的 AI 服务。

---

## 面试问题（本章）

1. Chat Completion 的请求体核心字段有哪些？`messages` 为什么是数组？
2. `temperature` 和 `top_p` 分别怎么控制随机性？它们能同时调吗？为什么？
3. `max_tokens` 限制的是输入还是输出？设太小会怎样？如何判断被截断？
4. 响应里的 `finish_reason` 有哪些取值？分别代表什么，工程上要怎么处理？
5. `usage` 的三个字段分别是什么？为什么说它等于账单？
6. 为什么说「模型是无状态的」？这对多轮对话的实现意味着什么？
7. 描述一次 Chat Completion 请求从发出到返回的完整生命周期。

---

## 我的练习答案

> 章末问题：**「一次 AI 请求到底经历了什么？」**
>
> 请从「组装请求 → 鉴权限流 → Tokenization → 自回归推理 → 组装响应 → 客户端处理」这条线，
> 用你自己的话完整讲一遍，并说明每个环节你作为 Java 工程师要关注什么。

```
（在这里写你的答案）
```

---

> ⏸ **本章到此暂停。** 请回答章末问题：**"一次 AI 请求到底经历了什么？"**
> 你回答后，我会点评并带你进入 **第三章：Message 和 Prompt 设计**。