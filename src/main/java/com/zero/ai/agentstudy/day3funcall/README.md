# Day3：Function Calling —— Agent 真正开始诞生

> 本包（`com.zero.ai.agentstudy.day3funcall`）是 Day3 的独立学习空间，不影响 `demo/`、`service/` 等既有学习代码。
>
> 学习方式：**大学教授 + 企业导师**。每章按"五段式深度框架"讲解，讲完暂停，你完成练习后再进入下一章。

---

## 教学框架（每章五段式）

| 段落 | 目标 | 你能获得 |
|------|------|----------|
| ① 为什么学（核心价值） | 讲清痛点与重要性 | 明白"不学会死在哪" |
| ② 是什么（概念与原理） | 清晰定义 + 底层机制 + ASCII 图解 | 建立准确心智模型 |
| ③ 怎么用（实战演练） | 可运行 Java(Spring AI) + Python 代码 | 从"看懂"到"会做" |
| ④ 用在哪（应用场景） | 真实 Agent 项目落地案例 | 理解商业价 |
| ⑤ 避坑与优化（进阶提升） | 常见错误 + 性能 + 工程化 + 面试题 | 达到企业级水准 |

---

## 目录

- [第一章：为什么 Agent 需要 Tool](#第一章为什么-agent-需要-tool)
- [第二章：Function Calling 工作原理](#第二章function-calling-工作原理)
- [第三章：Tool 设计原则](#第三章tool-设计原则)
- [第四章：Spring AI Tool Calling](#第四章spring-ai-tool-calling)
- [第五章：Java 实现三个 Tool](#第五章java-实现三个-tool)
- [第六章：多个 Tool 协同工作](#第六章多个-tool-协同工作)
- [第七章：企业最佳实践](#第七章企业最佳实践)
- [第八章：完成 Agent Assistant V1](#第八章完成-agent-assistant-v1)

---

## 学习进度追踪

| 章节 | 主题 | 状态 |
|------|------|------|
| 第一章 | 为什么 Agent 需要 Tool | 已完成 |
| 第二章 | Function Calling 工作原理 | 已完成 |
| 第三章 | Tool 设计原则 | 已完成 |
| 第四章 | Spring AI Tool Calling | 已完成 |
| 第五章 | Java 实现三个 Tool | 已完成 |
| 第六章 | 多个 Tool 协同工作 | 已完成 |
| 第七章 | 企业最佳实践 | 已完成 |
| 第八章 | Agent Assistant V1 | 已完成 |

---

## 对话记录归档

每完成一章，会在 `chatlog/` 目录下生成一份 `第X章-对话记录.md`，用于沉淀学习过程。

---
<!-- CHAPTERS_START -->

# 第一章：为什么 Agent 需要 Tool

---

## ① 为什么学（核心价值）

### 一个真实的商业场景

假设你是某电商公司的技术负责人，老板说："给我做一个 AI 客服，能自动帮用户查订单、查物流、发退款。"

你兴冲冲接入了 GPT-4，结果测试时发生了这样的对话：

```
用户：我的订单 20240612 到哪了？
AI  ：您的订单已经在派送途中，预计明天送达。   ← 【它在编！它根本没查数据库】
```

这就是**幻觉（Hallucination）**。大模型为了"把话说圆"，会编造一个听起来合理但完全错误的答案。在电商场景，这意味着：

- 用户投诉（"你说明天到，结果一周没来"）
- 客服工单暴增
- 品牌信任崩塌

**痛点根源**：大模型是"闭卷考试"，它只能靠脑子里训练时记住的东西答题，无法"翻书"（查实时数据）、无法"动手"（调接口）。

### Tool 解决的三大痛点

| 痛点 | 没有 Tool | 有了 Tool |
|------|-----------|-----------|
| **实时性** | 不知道今天股价/天气/订单状态 | 调 API 拿实时数据 |
| **精确性** | `12345 × 6789` 经常算错 | 调计算器，100% 正确 |
| **行动力** | 只能"说"发邮件，不能真发 | 调邮件接口，真的发出去 |

### 为什么这是 Agent 的分水岭

> **一句话价值：Tool 是 LLM 从"会聊天"进化到"会干活"的唯一通道。**

学不会 Tool，你做的永远是"聊天玩具"；学会了 Tool，你才真正踏入 Agent 开发的大门。后面所有内容（RAG、多智能体、工作流）都建立在 Tool 之上。

---

## ② 是什么（概念与原理）

### 精确定义

**Tool（工具 / Function）**：一段可被大模型"按需调用"的、有明确输入输出契约的外部函数。大模型不执行它，只决定"是否调用"和"传什么参数"。

**Agent（智能体）**：`Agent = LLM（大脑） + Tools（手脚） + 编排逻辑（神经）`。它能感知输入、自主决策、调用工具、观察结果、循环推进，直到完成任务。

### 核心原理：大脑与手脚分离

大模型底层是一个**自回归的下一个 token 预测器**。它做的唯一一件事是：给定前文，预测下一个最可能的字。它**没有任何执行能力**——不能联网、不能读文件、不能算数。

Function Calling 的天才之处在于：**它把"执行"这件事，用一个约定协议"外包"给了应用程序。**

- 大模型被告知："你有这些工具可用，格式如下……"
- 大模型判断需要某个工具时，不直接执行，而是**输出一段结构化 JSON**："我要调用 X，参数是 Y"。
- 应用程序（你的 Java/Python 代码）解析这段 JSON，**真正执行**函数。
- 结果再塞回对话，大模型据此生成最终回答。

### LLM 与 Agent 的本质区别（对比表）

| 维度 | 纯 LLM（聊天机器人） | Agent |
|------|---------------------|-------|
| 能力边界 | 训练语料内的知识 | 训练知识 + 无限外部工具 |
| 实时数据 | ❌ | ✅（调 API） |
| 精确计算 | ❌（猜） | ✅（调工具） |
| 真实动作 | ❌（只说） | ✅（真做：发邮件/下单） |
| 决策循环 | 一问一答 | 感知→决策→行动→观察→再决策 |
| 本质 | 文本生成器 | 会用工具的问题解决者 |

### 图解流程（ASCII）

**对比图：聊天机器人 vs Agent**

```
════════════════ 没有 Tool：聊天机器人 ════════════════

  用户："订单 20240612 到哪了？"
      │
      ▼
  ┌──────────────────┐
  │      LLM         │  只能靠"脑内记忆"猜
  │ 下一个token预测   │
  └────────┬─────────┘
           ▼
  "已在派送途中"  ← 编造(幻觉)，因为它没法查数据库


════════════════ 有 Tool：Agent ════════════════

  用户："订单 20240612 到哪了？"
      │
      ▼
  ┌──────────────────┐   ①决策：这事我干不了，要调工具
  │      LLM         │──────────────────────────┐
  │  理解 + 决策      │                           ▼
  └────────▲─────────┘              {"tool":"queryOrder",
           │                         "args":{"id":"20240612"}}
           │④用结果组织人话                     │
           │                                    ▼②真正执行
  "您的包裹已到达杭州     ┌─────────────────────────────┐
   转运中心，预计今晚送达" │  应用后端(Java/Python)       │
           ▲              │  真的去查订单数据库/物流API   │
           │              └──────────────┬──────────────┘
           │③结果回传                     │
           └──────── {"status":"到达杭州转运中心"} ◀┘
```

**关键认知**：整条链路里，LLM 从没执行过任何代码。它只做了两次"说话"——第一次说"我要调工具"，第二次说"最终答案"。真正干活的是你的后端。

### Agent 完整架构（分层视角）

```
┌─────────────────────────────────────────────┐
│                用户 / 前端                     │
└───────────────────────┬─────────────────────┘
                        │ 自然语言请求
┌───────────────────────▼─────────────────────┐
│              编排层 (Orchestration)           │
│   · 组装 Prompt   · 管理多轮对话历史           │
│   · 解析 tool_calls   · 循环调度直到完成       │
└───────┬───────────────────────────┬─────────┘
        │调用                        │决策依据
┌───────▼────────┐          ┌────────▼────────┐
│   LLM (大脑)    │          │  Tool 注册表     │
│  决策/推理/表达  │◀────────│ 每个工具的       │
└────────────────┘  可用工具 │ name+描述+参数   │
                            └────────┬────────┘
                                     │选中并执行
                            ┌────────▼────────┐
                            │  Tool 实现层     │
                            │ DB/HTTP/计算/…   │
                            └─────────────────┘
```

---

## ③ 怎么用（实战演练）

> 本章重点是"建立认知"，完整可运行 Demo 在第四章跑通。这里先看"一个 Tool 长什么样"，感受"决策与执行分离"。

### Java（Spring AI 风格）

一个 Tool 就是一个带 `@Tool` 注解的普通方法。注解里的 `description` 是**唯一**告诉 LLM"何时该用我"的线索：

```java
@Component
public class OrderTool {

    /**
     * description 极其重要：它是 LLM 判断"要不要调我"的唯一依据。
     * 写得越清楚，LLM 选工具越准。
     */
    @Tool(description = "根据订单号查询订单的物流状态。当用户询问订单、包裹、快递到哪了时使用")
    public String queryOrder(String orderId) {
        // 真正干活：查数据库 / 调物流 API（这里先 mock）
        // 注意：返回结构化 JSON，而不是自然语言（第三章详解为什么）
        return "{\"orderId\":\"" + orderId + "\",\"status\":\"到达杭州转运中心\"}";
    }
}
```

对比"没有 Tool"时，LLM 只能编一句"已在派送途中"。有了这个 Tool，LLM 会输出"我要调 queryOrder(20240612)"，你的程序真去查，再把真实状态交给 LLM 组织成人话。

### Python（LangChain 风格，用于读开源）

```python
from langchain_core.tools import tool

@tool
def query_order(order_id: str) -> str:
    """根据订单号查询订单的物流状态。当用户询问订单、包裹、快递到哪了时使用。"""
    # 真正干活：查数据库 / 调物流 API（这里先 mock）
    return '{"orderId": "%s", "status": "到达杭州转运中心"}' % order_id
```

**读开源小抄**：看到 `@tool`、`Tool(...)`、`tools=[...]`、`bind_tools()`、`ToolNode`（LangGraph），都是在"注册工具给大脑"。它们的共同点：**函数注释/描述 = 给 LLM 看的说明书**。

### 三行话记住核心机制

1. LLM 只输出"调用意图"（JSON），不执行。
2. 你的程序解析意图并真正执行，拿到结果。
3. 结果回传 LLM，由它组织成最终回答。

---

## ④ 用在哪（应用场景）

| Agent 产品 | 典型 Tool | 价值 |
|-----------|-----------|------|
| **AI 客服** | 查订单、查物流、发退款、转人工 | 7×24 解决 80% 常见问题，人力成本↓ |
| **AI 知识库** | 向量检索(RAG)、文档解析 | 基于企业私有资料精准回答，杜绝幻觉 |
| **AI 办公助手** | 发邮件、建日程、查考勤、写周报 | "帮我约明早 10 点和张三的会"一句话搞定 |
| **AI 量化交易 Agent** | 查行情、算技术指标、下单、风控校验 | LLM 决策策略，Tool 保证价格/下单 100% 精确 |

### 落地案例拆解：AI 客服的一次退款

```
用户："订单 20240612 商品有质量问题，我要退款"

Agent 内部（多 Tool 协同，第六章会深入）：
  1. queryOrder("20240612")        → 确认订单存在、已签收
  2. checkRefundPolicy(orderId)     → 校验是否在退款期内
  3. createRefund(orderId, reason)  → 真正发起退款
  4. LLM 汇总 → "已为您发起退款，3-5 个工作日原路退回"
```

这里 LLM 是"调度指挥官"，每一步的"实事"都由 Tool 完成。**这就是 Agent 的商业价值：把人类客服的操作流程自动化。**

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **误以为 LLM 会自己执行代码** → 不会。它只产出调用意图，执行永远是你的后端。新手最大误区。
2. **让 LLM 直接算数/报实时数据** → 结果不可信（幻觉）。凡实时/精确，一律走 Tool。
3. **Tool 结果直接返给用户** → 一般应回传 LLM 组织语言（除非明确要原始数据/给前端渲染）。
4. **description 写得含糊** → LLM 选错工具或不调工具。描述必须写清"何时用我"。
5. **Tool 返回自然语言** → 后续无法程序化处理、二次调用时易出错。应返回结构化 JSON（第三章详解）。

### 性能与工程化优化

- **减少无谓工具调用**：能用一次调用拿全的数据，不要拆成多次（每次调用都要重新请求 LLM，慢且贵）。
- **Tool 超时与降级**：外部 API 可能挂，Tool 内部要有超时、重试、降级（返回"暂时查不到"而非抛异常）。
- **参数校验前置**：LLM 传的参数不一定合法，Tool 入口要校验（如订单号格式），非法直接返回错误 JSON。
- **幂等设计**：写操作类 Tool（下单、退款）要幂等，防止 LLM 重复调用造成重复下单。
- **可观测性**：每次工具调用记录 traceId、入参、耗时、结果，便于排查 LLM 为什么调错。

### 面试高频问题

- **Q：LLM 和 Agent 的本质区别？**
  A：LLM 只能生成文本；Agent = LLM + Tool + 编排，能感知环境、自主决策并执行真实动作，形成"感知-决策-行动-观察"循环。
- **Q：Function Calling 时，代码是谁执行的？**
  A：永远是应用后端。LLM 只输出结构化调用意图（JSON），不具备执行能力。
- **Q：为什么不让 LLM 直接算数或查实时数据？**
  A：LLM 是概率预测不是计算器/数据库，会产生幻觉，精度与实时性不可靠，必须交给 Tool。
- **Q：LLM 靠什么决定调用哪个 Tool？**
  A：靠每个 Tool 的名称与 description（自然语言说明），LLM 做语义匹配后选择——所以描述质量直接决定选择准确率。

### 最佳实践清单

- ✅ 凡"实时、精确、有副作用"的事，一律 Tool 化。
- ✅ LLM 只负责：理解意图 + 选工具 + 组织语言。
- ✅ Tool 的 description 当成"给 LLM 的产品说明书"来写。
- ✅ Tool 返回结构化 JSON，写操作保证幂等，全链路有日志。

---

## 本章总结

- **一句话**：LLM 是有大脑没手脚的天才，Tool 就是给它装上的手脚；`Agent = LLM + Tool + 编排`。
- **核心机制**：LLM 决策（输出调用意图）→ 程序执行 → 结果回传 → LLM 表达。全程 LLM 不执行代码。
- **商业价值**：Tool 让 AI 从"会聊天"变成"会干活"，是所有商业化 Agent 的地基。

---

### 本章练习

请你回答：**"为什么没有 Tool 的大模型只是聊天机器人？"**

> 提示：可以从"能力边界、实时性、精确性、行动力、决策循环"几个角度组织，2~4 句用自己的话说清楚即可。

答完我再进入 **第二章：Function Calling 工作原理**（深入 OpenAI 协议格式、Spring AI 内部机制、LLM 如何决策调哪个工具）。

<!-- CHAPTER1_END -->

---

# 第二章：Function Calling 工作原理

---

## ① 为什么学（核心价值）

### 一个让你"顿悟"的问题

你学了第一章，知道"Tool 是给 LLM 装手脚"。但如果你现在被要求写代码实现一个 Agent，你的第一个问题一定是：

> **"LLM 到底是怎么'说'出它要调哪个工具的？它凭什么能输出这么精确的 JSON？"**

这就是 Function Calling 的底层魔法。不理解它，你就永远停留在"黑盒调用"——出了 Bug 不知道怎么查，选错了工具不知道怎么调。

### 为什么必须学透

| 不懂的后果 | 具体表现 |
|-----------|---------|
| **调不通** | LLM 返回的不是 tool_calls 而是普通文本，你解析失败 |
| **调不准** | LLM 选了错误的工具、传了错误的参数，你一脸懵 |
| **调不稳** | 参数格式时对时错，生产环境随机爆炸 |
| **优化不了** | 不知道如何让 LLM 更准地选工具，只能瞎改 description |

**一句话价值**：Function Calling 是 Agent 的"神经传导系统"，学透了，你就能精准控制 LLM 的"手脚"。

---

## ② 是什么（概念与原理）

### 精确定义

**Function Calling（工具调用）**：一种 LLM 与应用程序之间的**结构化通信协议**。它不是让 LLM 执行函数，而是让 LLM 在"需要做某件事"时，**输出一段符合约定的 JSON**，由应用程序解析并真正执行。

关键：这是 **LLM 输出格式的一种能力**，不是 LLM 获得了执行能力。模型训练时见过大量"函数调用格式"的样本，学会了"当需要做某事时，输出这种 JSON 而不是自然语言"。

### OpenAI Function Calling 协议（完整拆解）

这是整个行业的**事实标准**，所有模型（DeepSeek、Qwen、Gemini）都兼容这套格式。

#### 第一步：你告诉 LLM 有哪些工具（请求侧）

```json
// 你的 POST /v1/chat/completions 请求体里多了一个 tools 数组
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "user", "content": "北京今天天气怎么样？"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "getWeather",           // ← 工具名（唯一标识）
        "description": "查询指定城市的实时天气。当用户问天气、温度、下雨时使用",  // ← LLM 选工具的"说明书"
        "parameters": {                   // ← JSON Schema 定义参数格式
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "城市名，如北京、上海"
            }
          },
          "required": ["city"]
        }
      }
    }
  ]
}
```

**关键点**：`tools` 数组是你发给 LLM 的"菜单"。LLM 根据用户问题 + 每个工具的 name/description 做**语义匹配**，决定"要不要调、调哪个、传什么参数"。

#### 第二步：LLM 返回调用意图（响应侧）

```json
// 如果 LLM 觉得需要调工具，它的回复长这样：
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,               // ← 没有自然语言！content 是 null
      "tool_calls": [{                // ← 这才是 LLM 真正的"输出"
        "id": "call_abc123",          // 调用 ID（用于后续关联结果）
        "type": "function",
        "function": {
          "name": "getWeather",        // ← 要调哪个工具
          "arguments": "{\"city\":\"北京\"}"  // ← 参数（JSON 字符串）
        }
      }]
    },
    "finish_reason": "tool_calls"    // ← 关键：结束原因是"要调工具"而非"stop"
  }]
}
```

**关键点**：
- `content` 是 **null**——LLM 决定调工具时，不输出自然语言。
- `finish_reason = "tool_calls"` 是信号：别停，去执行工具，把结果再喂回来。
- `arguments` 是 JSON 字符串（不是对象），你需要反序列化。

#### 第三步：你把结果喂回去（第二轮请求）

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "user", "content": "北京今天天气怎么样？"},
    {"role": "assistant", "content": null, "tool_calls": [...]},  // ← 保留 LLM 的调用意图
    {"role": "tool", "tool_call_id": "call_abc123", "content": "北京晴，26℃"}  // ← 工具结果
  ]
}
```

**关键点**：
- `role = "tool"` 是专门角色，告诉 LLM"这是你刚才调的工具返回的结果"。
- `tool_call_id` 必须和上一步的 `id` 对应，否则 LLM 不知道是哪次调用的结果。
- 然后 LLM 用这个结果组织最终回答：`"北京今天晴，26℃，适合出门。"`

### 完整三阶段流程图（ASCII）

```
═══════════════════ OpenAI Function Calling 完整协议 ═══════════════════

 阶段①：请求（你 → LLM）
 ┌──────────────────────────────────────────────┐
 │  POST /v1/chat/completions                 │
 │  {                                         │
 │    "messages": [{"role":"user","content":"北京天气？"}],
 │    "tools": [{                             │
 │      "name":"getWeather",                   │  ← 工具菜单
 │      "description":"查询指定城市实时天气",      │
 │      "parameters":{"city":{"type":"string"}} │
 │    }]                                      │
 │  }                                         │
 └──────────────────┬───────────────────────────┘
                   │ LLM 语义匹配：用户问天气 → getWeather 最匹配
                   ▼
 阶段②：响应（LLM → 你）
 ┌──────────────────────────────────────────────┐
 │  {                                         │
 │    "choices":[{"message":{                  │
 │      "content": null,                       │  ← 不输出自然语言
 │      "tool_calls":[{                      │
 │        "id":"call_abc123",                 │
 │        "function":{                        │
 │          "name":"getWeather",              │  ← 选中工具
 │          "arguments":"{\"city\":\"北京\"}"   │  ← 参数(JSON)
 │        }                                   │
 │      }]                                    │
 │    }, "finish_reason":"tool_calls"}]      │  ← 信号：去执行
 │  }                                         │
 └──────────────────┬───────────────────────────┘
                   │ 你的程序解析 JSON，真正执行
                   ▼
 阶段③：回传结果 + 最终回答
 ┌──────────────────────────────────────────────┐
 │  POST /v1/chat/completions                 │
 │  {                                         │
 │    "messages": [                            │
 │      {"role":"user","content":"北京天气？"},   │
 │      {"role":"assistant","content":null,     │
 │       "tool_calls":[...]},                  │  ← 保留调用记录
 │      {"role":"tool",                        │
 │       "tool_call_id":"call_abc123",         │
 │       "content":"北京晴，26℃"}               │  ← 工具结果
 │    ]                                       │
 │  }                                         │
 └──────────────────┬───────────────────────────┘
                   │ LLM 用结果组织最终回答
                   ▼
  "北京今天晴，26℃，适合出门。"  ← finish_reason="stop"
```

### Spring AI 内部机制（怎么封装的）

Spring AI 把上面三步封装成了**一个自动循环**，你只需要：

1. 用 `@Tool` 注解注册工具
2. 调用 `ChatClient.call()` 时传入工具列表
3. Spring AI 自动：发请求 → 解析 tool_calls → 执行工具 → 回传结果 → 再请求 → 直到 finish_reason="stop"

内部伪代码（帮你理解黑盒）：

```java
// Spring AI 内部循环（简化版）
while (true) {
    response = llm.chat(request);  // 发请求
    if (response.finishReason == "stop") {
        return response.content;    // 完成，返回最终回答
    }
    if (response.finishReason == "tool_calls") {
        for (toolCall : response.toolCalls) {
            result = executeTool(toolCall);     // 真正执行
            messages.add(new ToolMessage(result)); // 结果加入对话
        }
        continue;  // 再发请求，让 LLM 看结果
    }
}
```

### 为什么 LLM 不会真正执行代码（深入解释）

LLM 的底层是 Transformer 架构，它做的唯一一件事是：

```
输入 token 序列 → 多层自注意力计算 → 输出下一个 token 的概率分布 → 采样一个 token
```

它**没有 CPU 执行单元**，没有系统调用能力，没有网络栈。它只是一个巨大的矩阵乘法器。

Function Calling 的"魔法"在于：训练数据里包含了大量"函数调用格式"的样本，模型学会了——当上下文暗示"需要外部数据/动作"时，输出 JSON 格式比输出自然语言更"合理"。

**一句话**：LLM 不是"学会了执行代码"，而是"学会了输出一种特定格式的文本，这种文本恰好能被程序解析为函数调用"。

---

## ③ 怎么用（实战演练）

### Java（Spring AI 完整可运行 Demo）

> 以下代码在第四章会完整跑通。这里先看"骨架"。

```java
// ============ 1. 定义工具 ============
@Component
public class WeatherTool {
    @Tool(description = "查询指定城市的实时天气。当用户问天气、温度、下雨时使用")
    public String getWeather(@ToolParam(description = "城市名，如北京、上海") String city) {
        // 真实场景：调天气 API。这里模拟
        return "{\"city\":\"" + city + "\",\"temp\":26,\"desc\":\"晴\"}";
    }
}

// ============ 2. 注册并调用 ============
@Service
public class AgentService {
    private final ChatClient chatClient;

    public String chat(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .tools(new WeatherTool())  // ← 注册工具，Spring AI 自动处理后续
                .call()
                .content();  // ← 最终返回的是 LLM 用工具结果组织好的自然语言
    }
}
```

**对比：如果不用 Spring AI，你需要手写三阶段协议（约 80 行代码）。Spring AI 把这 80 行变成了 5 行。**

### Python（LangChain 风格）

```python
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool

@tool
def get_weather(city: str) -> str:
    """查询指定城市的实时天气。当用户问天气、温度、下雨时使用。"""
    return '{"city":"%s","temp":26,"desc":"晴"}' % city

llm = ChatOpenAI(model="gpt-4o")
llm_with_tools = llm.bind_tools([get_weather])  # ← 关键：bind_tools 把工具注入 LLM

# 调用后，response 里会有 tool_calls 字段
response = llm_with_tools.invoke("北京今天天气？")
print(response.tool_calls)  # [{'name': 'get_weather', 'args': {'city': '北京'}}]
```

**读开源小抄**：`bind_tools()` 本质就是把 Python 函数的签名 + docstring 转成 OpenAI tools 数组格式，注入到每次请求里。

---

## ④ 用在哪（应用场景）

### 真实案例：AI 量化交易 Agent 的一次决策

```
用户："帮我分析一下茅台最近能不能买"

Agent 内部 Function Calling 链路：
  ① LLM 输出 tool_calls: [
       {name:"getStockPrice", args:{code:"600519"}},   // 查实时股价
       {name:"getKline", args:{code:"600519",days:30}},  // 查 30 日 K 线
       {name:"getNews", args:{keyword:"茅台"}}            // 查相关新闻
     ]
  ② 程序执行三个工具，拿到真实数据
  ③ 结果回传 LLM，LLM 分析后输出："茅台当前 1680 元，30 日均线走平，
     近期无重大利好，建议观望。"
```

**关键**：LLM 不是"自己算"技术指标，而是"决定要调哪些计算工具"。价格的精确性由 Tool 保证，策略的合理性由 LLM 判断。

### 为什么这就是 Workflow 的基础

Workflow（工作流）= 多个 Tool 按一定顺序执行，上一个的输出是下一个的输入。

```
WeatherTool(城市) → 拿到天气 → EmailTool(天气+收件人) → 发邮件
```

Function Calling 提供了"LLM 能按需编排多个工具"的能力，这就是 Workflow 的底层。第六章会深入。

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **没检查 finish_reason** → 直接读 content，发现是 null 就崩了。必须先判断 `finish_reason == "tool_calls"`。
2. **忘记把 tool 结果回传** → 只调了一次，拿到 tool_calls 就停了，LLM 没机会组织最终回答。
3. **tool_call_id 没对上** → 结果回传时 id 写错，LLM 不知道是哪次调用的结果，输出混乱。
4. **arguments 是 JSON 字符串不是对象** → 直接用 `toolCall.arguments.city` 会报错，必须先 `JSON.parse()`。
5. **工具结果太长** → 超过模型上下文窗口，被截断。大结果要摘要后再回传。

### 性能与工程化优化

- **并行调用**：LLM 一次返回多个 tool_calls 时，如果它们之间没有依赖，可以并行执行（省时）。
- **缓存工具结果**：同一参数短时间内重复调用，缓存结果避免重复请求 LLM。
- **超时与重试**：每个工具调用设超时，失败重试 1~2 次，最终降级返回"暂时查不到"。
- **Token 预算**：工具结果回传会消耗大量 token，大结果要截断/摘要。
- **流式 + Tool Calling**：目前主流模型不支持"边流式输出边调工具"（因为 tool_calls 是完整 JSON），需要等非流式完成后再执行工具。

### 面试高频问题

- **Q：Function Calling 时，LLM 内部发生了什么？**
  A：模型在训练时见过大量"函数调用格式"的样本，学会了当上下文暗示需要外部数据时，输出 JSON 比输出自然语言更合理。本质仍是 token 预测，只是输出格式变了。
- **Q：LLM 如何决定调用哪个 Tool？**
  A：基于每个 Tool 的 name + description 做语义匹配。用户说"天气"→ 匹配到 description 含"天气"的工具。描述质量直接决定准确率。
- **Q：为什么 tool_calls 里 content 是 null？**
  A：因为 LLM 决定"这件事我不该说话，该交给工具"，所以不输出自然语言。finish_reason = "tool_calls" 就是信号。
- **Q：如果 LLM 返回了 tool_calls 但格式不对怎么办？**
  A：用 JSON Schema 约束（部分模型支持 `strict` 模式），或在解析层做容错（如修复常见格式错误）。

### 最佳实践清单

- ✅ 每次请求后检查 finish_reason，是 "tool_calls" 就执行工具并回传。
- ✅ 工具结果用 role="tool" + tool_call_id 精确关联。
- ✅ 工具 description 写清"何时用我"（这是 LLM 选工具的唯一依据）。
- ✅ 大结果摘要后回传，控制 token 消耗。
- ✅ 无依赖的工具调用并行执行。

---

## 本章总结

- **一句话**：Function Calling 是 LLM 输出格式的一种能力，不是执行能力。LLM 学会在需要时输出 JSON 而非自然语言。
- **三阶段协议**：发工具菜单 → LLM 返回调用意图 → 执行并回传结果 → LLM 组织最终回答。
- **Spring AI 封装**：把三阶段自动循环，你只需 `@Tool` + `chatClient.tools(...)`。
- **核心认知**：LLM 是"学会了输出特定格式的文本"，不是"学会了执行代码"。

---

### 本章练习

请画出 **Function Calling 完整调用流程**（从用户提问到最终回答，标出三阶段 + 关键字段）。

> 提示：用 ASCII 或文字描述均可，关键是把 `tools` 数组、`tool_calls`、`finish_reason`、`role=tool` 这几个要素串起来。

画完我进入 **第三章：Tool 设计原则**（为什么 Tool 必须返回 JSON、单一职责、企业设计规范）。

<!-- CHAPTER3_START -->

---

# 第三章：Tool 设计原则

> 说明：本文档中的代码块是"教学示例"，编辑器可能把 Markdown 里的 JSON/Java 片段误报为语法错误，**不影响项目编译**，可忽略。

---

## ① 为什么学（核心价值）

### 一个血泪教训

某团队做 AI 客服，Tool 这样设计的：

```
// 反例：Tool 返回自然语言
@Tool(description = "查询订单")
public String queryOrder(String orderId) {
    return "您的订单已经到达杭州，预计明天送达哦～";  // ← 返回一句人话
}
```

结果出了两个大问题：

1. **LLM 二次加工出错**：LLM 拿到"预计明天送达哦～"，又改写成"大概后天到"，语义漂移。
2. **无法程序化处理**：产品经理想加个"如果已签收就弹评价框"，但 Tool 返回的是一句话，代码没法判断"是否已签收"。

改成返回结构化 JSON `{"status":"IN_TRANSIT","city":"杭州","eta":"2024-06-13"}` 后，问题全部解决：LLM 照实翻译，前端能精确判断状态。

### 为什么 Tool 设计是分水岭

| 设计好 | 设计差 |
|--------|--------|
| LLM 选得准、传参对 | LLM 频繁选错工具 |
| 结果可程序化处理 | 结果只能"看"，不能"用" |
| 一个工具一件事，易维护 | 一个工具干十件事，改一处崩全部 |
| 几十个工具也井井有条 | 十个工具就乱成一团 |

**一句话价值**：Tool 设计能力，直接决定你的 Agent 能不能规模化（从 3 个工具扩展到 100 个）。

---

## ② 是什么（概念与原理）

### 原则一：Tool 为什么返回 JSON（而非自然语言）

核心原因有三：

1. **机器可解析**：JSON 有明确字段，代码能精确取值（`result.status`），自然语言不行。
2. **LLM 更可控**：给 LLM 结构化数据，它只负责"翻译成人话"，不会二次编造。
3. **可组合**：一个 Tool 的 JSON 输出，能直接作为下一个 Tool 的输入（Workflow 基础）。

```
自然语言："您的订单到杭州了"     → LLM 可能改写成"到江浙一带" → 语义漂移
结构化  ：{"city":"杭州","status":"IN_TRANSIT"} → LLM 照实翻译 → 精准
```

### 原则二：Tool 为什么不要返回自然语言

**分工原则**：LLM 负责"表达"，Tool 负责"提供事实"。

如果 Tool 也返回自然语言，就相当于"两个人都在说话"，LLM 会对 Tool 的话再加工，导致：
- 语义漂移（改写走样）
- 幻觉放大（LLM 基于模糊描述再脑补）
- 无法程序化（前端/后续逻辑拿不到结构化字段）

**记住**：Tool 是"数据源"，不是"发言人"。

### 原则三：Tool 单一职责（SRP）

一个 Tool 只做一件事，且把这件事做好。

```
反例（万能工具）：
  manageOrder(action, orderId, ...)   // action=query/cancel/refund/...
  问题：LLM 难以判断 action 传什么；改退款逻辑可能影响查询

正例（单一职责）：
  queryOrder(orderId)       // 只查
  cancelOrder(orderId)      // 只取消
  refundOrder(orderId)      // 只退款
  好处：LLM 语义匹配清晰；各自独立演进、独立测试
```

### 原则四：Tool 设计规范（六要素）

一个企业级 Tool 应该定义清楚：

| 要素 | 说明 | 示例 |
|------|------|------|
| **name** | 动词+名词，见名知意 | `queryStockPrice` |
| **description** | 写清"何时用我"，给 LLM 看 | "查询指定股票的实时价格。用户问股价/行情时用" |
| **参数** | 每个参数有类型 + 描述 + 是否必填 | `code: string, 股票代码, 必填` |
| **返回** | 结构化 JSON，字段固定 | `{"code","price","time"}` |
| **异常** | 失败也返回 JSON，不抛异常 | `{"error":"股票代码不存在"}` |
| **幂等** | 写操作可重复调用不出错 | 退款用 requestId 去重 |

### 图解：好 Tool vs 坏 Tool

```
┌──────────────── 坏 Tool ────────────────┐
│ manageEverything(type, data)            │
│  · 名字模糊 → LLM 不知何时调             │
│  · 参数万能 → LLM 不知传什么             │
│  · 返回自然语言 → 无法程序化              │
│  · 一改全崩 → 难维护                     │
└─────────────────────────────────────────┘

┌──────────────── 好 Tool ────────────────┐
│ queryStockPrice(code: 股票代码)          │
│  · 名字清晰 → LLM 一看就懂               │
│  · 参数明确 → LLM 好传参                 │
│  · 返回 {"code","price","time"} → 可组合  │
│  · 单一职责 → 独立演进/测试              │
└─────────────────────────────────────────┘
```

<!-- CHAPTER3_PART2 -->

---

## ③ 怎么用（实战演练）

### Java：一个规范的企业级 Tool

```
@Component
public class StockTool {

    /**
     * name 由方法名决定：queryStockPrice（动词+名词，清晰）
     * description 写清"何时用我"
     */
    @Tool(description = "查询指定股票的实时价格与涨跌幅。当用户询问股价、行情、某只股票多少钱时使用")
    public String queryStockPrice(
            @ToolParam(description = "股票代码，如 600519（贵州茅台）") String code) {

        // 1. 参数校验前置
        if (code == null || !code.matches("\\d{6}")) {
            // 2. 异常也返回结构化 JSON，不抛异常
            return "{\"error\":\"股票代码格式错误，应为6位数字\"}";
        }

        // 3. 真正查询（这里 mock）
        // 4. 返回结构化 JSON，字段固定
        return "{\"code\":\"" + code + "\",\"name\":\"贵州茅台\","
             + "\"price\":1680.50,\"changePct\":1.23,\"time\":\"2024-06-12 15:00\"}";
    }
}
```

### Python 对照（读开源用）

```
from langchain_core.tools import tool
import json, re

@tool
def query_stock_price(code: str) -> str:
    """查询指定股票的实时价格与涨跌幅。当用户询问股价、行情时使用。code: 6位股票代码"""
    if not re.match(r"^\d{6}$", code or ""):
        return json.dumps({"error": "股票代码格式错误"})
    return json.dumps({"code": code, "name": "贵州茅台",
                       "price": 1680.50, "changePct": 1.23})
```

### 设计一个 Tool 的思考清单

1. 这个 Tool 只做一件事吗？（否 → 拆分）
2. 名字动词+名词、见名知意吗？
3. description 写清"何时用我"了吗？
4. 每个参数有类型+描述吗？
5. 返回是结构化 JSON 吗？字段固定吗？
6. 失败时返回 error JSON 而非抛异常吗？
7. 如果是写操作，幂等吗？

---

## ④ 用在哪（应用场景）

### 企业 Tool 设计案例：AI 量化交易的 StockTool 族

真实项目里，一个"股票"领域会拆成一族单一职责的 Tool：

| Tool | 职责 | 返回 JSON 关键字段 |
|------|------|-------------------|
| `queryStockPrice` | 查实时价 | code, price, changePct |
| `queryKline` | 查 K 线 | code, days, points[] |
| `queryFinance` | 查财报 | code, pe, roe, revenue |
| `placeOrder` | 下单（写，幂等） | orderId, status |
| `queryPosition` | 查持仓 | positions[] |

**LLM 的角色**：用户说"帮我看看茅台能不能买"，LLM 自动编排 `queryStockPrice` + `queryKline` + `queryFinance`，拿到结构化数据后综合分析。每个 Tool 各司其职，价格由 Tool 保证精确。

### 为什么结构化返回是 Workflow 的前提

```
queryStockPrice(600519) → {"price":1680} 
        │ price 字段可直接被下一步使用
        ▼
riskCheck(price=1680, budget=100000) → {"canBuy":true,"maxQty":59}
        │ maxQty 字段可直接被下一步使用
        ▼
placeOrder(code=600519, qty=59) → {"orderId":"x","status":"SUCCESS"}
```

如果每一步返回的是自然语言，这条流水线根本串不起来。**结构化 = 可组合 = Workflow 基础。**

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **返回自然语言** → LLM 二次加工语义漂移，前端无法程序化。必返 JSON。
2. **万能工具** → 一个 Tool 塞 N 个功能，LLM 选参困难、维护灾难。坚持单一职责。
3. **description 写实现细节** → 写"调用了 XX 微服务的 YY 接口"没用，要写"用户问什么时候调我"。
4. **抛异常而非返回 error** → 异常会中断 Agent 循环，应返回 `{"error":"..."}` 让 LLM 优雅处理。
5. **返回字段不稳定** → 时有时无的字段让后续逻辑崩溃，字段结构要固定。
6. **参数无描述** → LLM 猜参数含义，传错值。每个参数都要写描述。

### 性能与工程化优化

- **返回精简**：只返 LLM/前端需要的字段，别把整个 DB 行塞回去（省 token）。
- **枚举值标准化**：状态用固定枚举（`IN_TRANSIT`/`DELIVERED`），别用中文描述，便于程序判断。
- **错误码体系**：error JSON 带 code（如 `INVALID_PARAM`/`NOT_FOUND`），便于统一处理。
- **版本兼容**：Tool 返回结构演进时用加字段而非改字段，避免破坏已有逻辑。
- **描述可迭代**：上线后观察 LLM 选错工具的 case，反向优�� description（这是调优主战场）。

### 面试高频问题

- **Q：Tool 为什么返回 JSON 而不是自然语言？**
  A：JSON 机器可解析、LLM 更可控（只翻译不编造）、可组合（作为下一 Tool 输入）。自然语言会导致语义漂移和无法程序化。
- **Q：Tool 设计的单一职责原则怎么理解？**
  A：一个 Tool 只做一件事。好处是 LLM 语义匹配清晰、各工具独立演进和测试、易维护扩展。
- **Q：Tool 执行失败应该抛异常还是返回错误？**
  A：返回结构化 error JSON。抛异常会中断 Agent 循环，返回 error 能让 LLM 优雅告知用户或重试。
- **Q：description 应该写什么？**
  A：写"何时该用我"（面向 LLM 的语义线索），而不是实现细节。这是 LLM 选对工具的唯一依据。

### 最佳实践清单

- ✅ Tool 返回结构化 JSON，字段固定，用枚举值。
- ✅ 一个 Tool 只做一件事（单一职责）。
- ✅ name 动词+名词；description 写"何时用我"；每个参数有描述。
- ✅ 失败返回 error JSON（带错误码），不抛异常。
- ✅ 写操作幂等；返回精简；结构演进只加不改。

---

## 本章总结

- **一句话**：Tool 是"数据源"不是"发言人"——返回结构化 JSON，把"说话"留给 LLM。
- **四大原则**：返回 JSON、不返自然语言、单一职责、六要素规范（name/description/参数/返回/异常/幂等）。
- **规模化关键**：好的 Tool 设计让你从 3 个工具平滑扩展到 100 个。

---

### 本章练习

请你**设计一个 StockTool 的数据结构**，包括：

1. 工具名（name）
2. description（写清何时用）
3. 输入参数（类型 + 描述 + 是否必填）
4. 返回的 JSON 结构（列出字段 + 类型 + 含义）
5. 失败时的 error JSON 结构

> 提示：可参考本章 ④ 的 StockTool 族，先设计最核心的"查实时价"这一个即可。

设计完我进入 **第四章：Spring AI Tool Calling**（`@Tool`/`ToolCallback`/注册/调用/执行流程，一步步跑通 Java Demo）。

<!-- CHAPTER3_END -->

---

# 第四章：Spring AI Tool Calling

> 本章代码**已在项目中真实实现并编译通过**，位于本包：
> - 工具：`day3funcall/tool/WeatherTool.java`
> - 服务：`day3funcall/service/Day3AgentService.java`
> - 入口：`day3funcall/controller/Day3Controller.java`
>
> 环境已升级：Spring Boot 3.4.5 + Spring AI 1.0 GA（`spring-ai-starter-model-openai`）。

---

## ① 为什么学（核心价值）

第二章我们知道 Function Calling 有三阶段协议（发 tools → 解析 tool_calls → 回传结果）。如果手写，一次工具调用要写约 80 行"协议胶水代码"，还要处理循环、异常、并行。

**Spring AI 的价值**：把这 80 行变成 3 行。你只管写业务工具，协议全自动。

```
手写协议：发请求→判断finish_reason→解析tool_calls→执行→构造tool消息→再发→循环... （易错、繁琐）
Spring AI：chatClient.prompt().user(msg).tools(weatherTool).call().content();  （一行）
```

**一句话价值**：Spring AI 让 Java 工程师用最熟悉的"注解 + Bean"方式做 Agent，零��议负担。

---

## ② 是什么（概念与原理）

### 四个核心概念

| 概念 | 作用 | 对应代码 |
|------|------|---------|
| `@Tool` | 把普通方法标记为可被 LLM 调用的工具 | `WeatherTool.getWeather()` |
| `@ToolParam` | 描述工具参数，帮 LLM 正确传参 | `@ToolParam(description=...)` |
| `ChatClient` | 对话客户端，负责组装请求、自动执行工具循环 | `Day3AgentService` |
| `ChatModel` | 底层模型（由 starter 自动装配，读 application.yml） | 构造注入 |

### Tool 注册与调用流程

Spring AI 在你调 `.tools(weatherTool)` 时做了这些事：

```
① 反射扫描 weatherTool 对象里所有 @Tool 方法
      │
      ▼
② 把每个方法转成 OpenAI tools 数组格式
   （方法名→name，@Tool description→description，参数→JSON Schema）
      │
      ▼
③ 随请求发给 LLM
      │
      ▼
④ LLM 返回 tool_calls → Spring AI 反射调用对应 Java 方法
      │
      ▼
⑤ 方法返回值作为 tool 结果，自动回传 LLM
      │
      ▼
⑥ 循环直到 finish_reason=stop，返回最终 content
```

### 执行流程图（完整）

```
用户："北京天气怎么样，要带伞吗？"
   │
   ▼
Day3Controller.weather()
   │
   ▼
Day3AgentService.chatWithWeather()
   │  chatClient.prompt().user(msg).tools(weatherTool).call()
   ▼
┌─────────────── Spring AI 自动循环 ───────────────┐
│  ① 扫描 @Tool → 生成 tools 数组                   │
│  ② 发请求给 LLM（带 tools）                       │
│  ③ LLM 返回: tool_calls=[getWeather(city=北京)]   │
│  ④ 反射执行 WeatherTool.getWeather("北京")        │  ← 你的 Java 代码真正跑
│     返回 {"city":"北京","temp":26,"desc":"晴"...} │
│  ⑤ 结果作为 role=tool 消息回传 LLM                │
│  ⑥ LLM 生成: "北京今天晴，26℃，不用带伞"          │
└──────────────────────────────────────────────────┘
   │
   ▼
返回给用户："北京今天晴，26℃，湿度适中，不用带伞。"
```

---

## ③ 怎么用（实战演练）

### 第 1 步：定义工具（WeatherTool.java）

核心是 `@Tool` + `@ToolParam` + 返回 JSON：

```
@Component
public class WeatherTool {
    @Tool(description = "查询指定城市的实时天气。当用户询问天气、气温、是否下雨、要不要带伞时使用")
    public String getWeather(
            @ToolParam(description = "城市名称，如：北京、上海、杭州") String city) {
        if (city == null || city.isBlank()) {
            return "{\"code\":\"INVALID_PARAM\",\"message\":\"城市名不能为空\"}";
        }
        // 真实项目调天气 API，这里 mock
        return "{\"city\":\"" + city + "\",\"temp\":26,\"desc\":\"晴\",\"humidity\":55}";
    }
}
```

### 第 2 步：构建 ChatClient 并挂载工具（Day3AgentService.java）

```
@Service
public class Day3AgentService {
    private final ChatClient chatClient;
    private final WeatherTool weatherTool;

    public Day3AgentService(ChatModel chatModel, WeatherTool weatherTool) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.weatherTool = weatherTool;
    }

    public String chatWithWeather(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .tools(weatherTool)   // ← 关键：挂载工具，其余全自动
                .call()
                .content();
    }
}
```

### 第 3 步：暴露入口并运行（Day3Controller.java）

```
@RestController
@RequestMapping("/day3")
@RequiredArgsConstructor
public class Day3Controller {
    private final Day3AgentService day3AgentService;

    @GetMapping("/weather")
    public String weather(@RequestParam(defaultValue = "北京今天天气怎么样？") String msg) {
        return day3AgentService.chatWithWeather(msg);
    }
}
```

### 第 4 步：配置模型（application.yml）

```
spring:
  ai:
    openai:
      base-url: http://127.0.0.1:1234    # 你的 OpenAI 兼容服务
      api-key: ${AI_API_KEY:sk-1234}
      chat:
        options:
          model: your-model-name
          temperature: 0.7
```

### 运行与验证

```
启动应用后访问：
  http://localhost:8080/day3/weather?msg=北京今天天气怎么样，要带伞吗？

观察控制台日志：
  [Day3Agent] 收到用户输入: 北京今天天气怎么样...
  [WeatherTool] 被调用, city=北京        ← 证明 LLM 触发了工具调用！
  [Day3Agent] 最终回答: 北京今天晴...
```

看到 `[WeatherTool] 被调用` 就说明整条 Tool Calling 链路跑通了。

### Python 对照（读开源用）

```
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool

@tool
def get_weather(city: str) -> str:
    """查询指定城市的实时天气。当用户问天气、气温、下雨时使用。"""
    return '{"city":"%s","temp":26,"desc":"晴"}' % city

agent = ChatOpenAI(model="gpt-4o").bind_tools([get_weather])
# LangChain 需要你自己写循环执行 tool（或用 AgentExecutor/LangGraph 自动化）
```

**对比**：Spring AI 的 `.call()` 默认自动执行工具循环；LangChain 裸用 `bind_tools` 需自己写循环，通常配 `AgentExecutor` 或 LangGraph。

---

## ④ 用在哪（应用场景）

- **企业 Java 团队做 Agent**：Spring AI 是首选，因为团队已有 Spring 技术栈，`@Tool` 学习成本极低。
- **快速原型**：几个注解就能把现有的 Service 方法暴露成工具，快速验证 Agent 想法。
- **与现有系统集成**：工具方法内部可直接注入现有的 `@Service`/`Mapper`，复用企业已有能力（查库、调微服务）。

### 案例：把现有订单服务变成工具

```
@Component
@RequiredArgsConstructor
public class OrderTool {
    private final OrderService orderService;  // ← 直接复用现有业务 Service

    @Tool(description = "根据订单号查询订单状态")
    public String queryOrder(@ToolParam(description="订单号") String orderId) {
        Order o = orderService.getById(orderId);  // 复用已有逻辑
        return toJson(o);
    }
}
```

**价值**：企业不需要为 Agent 重写业务逻辑，只需给现有方法"包一层 @Tool"。

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **工具类没加 @Component** → Spring 扫描不到，注入失败。必须是 Spring Bean。
2. **@Tool 方法是 private** → 反射调不到，必须 public。
3. **description 用英文但用户说中文** → 影响匹配，description 语言应贴合使用语言。
4. **模型不支持 Function Calling** → 有些小模型没这能力，会返回普通文本而非 tool_calls。选支持的模型。
5. **base-url 配错** → Spring AI 默认拼 `/v1/chat/completions`，注意你的服务路径前缀。

### 性能与工程化优化

- **工具返回精简 JSON**：减少回传 token，降低成本和延迟。
- **给工具方法加超时/降级**：外部调用失败返回 error JSON，别让整个 Agent 卡死。
- **合理设置 temperature**：工具选择场景建议低温度（0~0.3），让 LLM 决策更确定。
- **日志埋点**：每个工具入口打 log（如本例 `[WeatherTool] 被调用`），线上排查必备。
- **多工具时控制数量**：一次挂载几十个工具会撑大 prompt，按场景动态挂载（第七章讲工具目录）。

### 面试高频问题

- **Q：Spring AI 的 @Tool 底层做了什么？**
  A：反射扫描标注方法，转成 OpenAI tools 数组格式随请求发送；LLM 返回 tool_calls 时反射调用对应方法，结果自动回传并循环，直到 finish_reason=stop。
- **Q：ChatClient 和 ChatModel 区别？**
  A：ChatModel 是底层模型抽象（自动装配）；ChatClient 是高层流式 API（builder 构建），封装了 prompt 组装、工具循环等。
- **Q：工具方法为什么要返回 JSON 而非对象？**
  A：Spring AI 会把返回值序列化后回传 LLM，返回结构化 JSON（或可序列化对象）便于 LLM 理解，避免 toString 出乱码。

### 最佳实践清单

- ✅ 工具类 @Component，工具方法 public + @Tool + @ToolParam。
- ✅ description 写清"何时用我"，语言贴合用户。
- ✅ 返回精简结构化 JSON，失败返 error JSON。
- ✅ 工具入口打日志，低 temperature 提升决策确定性。
- ✅ 复用现有 Service，不为 Agent 重写业务。

---

## 本章总结

- **一句话**：Spring AI 用 `@Tool` + `ChatClient.tools()` 把 Function Calling 三阶段协议全自动化。
- **四概念**：`@Tool`（标记工具）、`@ToolParam`（描述参数）、`ChatClient`（对话+自动循环）、`ChatModel`（底层模型）。
- **本章成果**：项目里已有可运行的 WeatherTool + Day3AgentService + Day3Controller，编译通过。

---

### 本章练习

启动应用（需配好可用的 OpenAI 兼容模型），访问：
`http://localhost:8080/day3/weather?msg=杭州今天要带伞吗？`

观察控制台是否打印 `[WeatherTool] 被调用`，并思考：**如果我再加一个 TimeTool，LLM 怎么知道该调 WeatherTool 还是 TimeTool？**

（这正是第五章的内容：实现三个 Tool，看 LLM 如何在多个工具间自动选择。）

<!-- CHAPTER4_END -->

---

# 第五章：Java 实现三个 Tool

> 本章代码已在项目中实现并编译通过：
> - `day3funcall/tool/WeatherTool.java`（第四章已建）
> - `day3funcall/tool/CalculatorTool.java`
> - `day3funcall/tool/TimeTool.java`
> - 多工具入口：`day3funcall/controller/Day3Controller.java` 的 `GET /day3/agent`

---

## ① 为什么学（核心价值）

第四章只有一个工具，LLM"没得选"。真实 Agent 都有多个工具，核心问题变成：

> **当有 3 个、30 个工具时，LLM 凭什么选对那一个？会不会选错？能不能一次调多个？**

本章通过实现 Calculator / Time / Weather 三个典型工具，让你亲眼看到：
- LLM 如何根据 description 自动路由到正确工具
- 一句话涉及多个能力时，LLM 如何一次调用多个工具
- 三种最常见的工具类型（计算/实时数据/外部API模拟）如何落地

**一句话价值**：单工具是玩具，多工具自动选择才是 Agent 的真实形态。

---

## ② 是什么（概念与原理）

### 三个工具的定位

| 工具 | 解决 LLM 的哪个残疾 | 返回字段 |
|------|--------------------|---------|
| `CalculatorTool` | 不擅长精确计算 | a, b, op, result |
| `TimeTool` | 不知道实时时间（知识截止） | time, zone, weekday |
| `WeatherTool` | 不知道实时外部数据 | city, temp, desc, humidity |

### LLM 如何在多工具间选择（路由原理）

```
用户："帮我算 1234 乘以 5678"
   │
   ▼
Spring AI 把 3 个工具都转成 tools 数组发给 LLM:
   [
     {name:"calculate",       description:"...精确计算、算数学题..."},
     {name:"getCurrentTime",  description:"...现在几点、今天几号..."},
     {name:"getWeather",      description:"...天气、气温、下雨..."}
   ]
   │
   ▼
LLM 做语义匹配：
   "算 X 乘以 Y" 与哪个 description 最匹配？
   → "精确计算、算数学题" 命中 calculate
   │
   ▼
返回 tool_calls: [calculate(a=1234, b=5678, op="multiply")]
```

**关键**：LLM 选工具靠的是 **description 的语义匹配**。描述写得越清晰、越贴合用户说法，选得越准。这就是为什么第三章强调"description 是给 LLM 的说明书"。

### 一次调用多个工具（并行）

```
用户："杭州天气如何？顺便告诉我现在几点"
   │
   ▼
LLM 识别出两个独立意图 → 一次返回两个 tool_calls:
   [ getWeather(city="杭州"), getCurrentTime(zone=null) ]
   │
   ▼
Spring AI 分别执行两个工具，把两个结果都回传
   │
   ▼
LLM 综合两个结果："杭州今天多云，22℃；现在是 15:30。"
```

这就是**多工具协同的雏形**，第六章会深入到"工具间有依赖"的 Workflow。

---

## ③ 怎么用（实战演练）

### CalculatorTool（计算器）

要点：LLM 算数不可靠 → 交给 Tool；op 用枚举 + 归一化兼容多种写法；除零兜底。

```
@Tool(description = "对两个数字做四则运算（加减乘除）。当用户需要精确计算、算数学题时使用")
public String calculate(
        @ToolParam(description = "第一个操作数") double a,
        @ToolParam(description = "第二个操作数") double b,
        @ToolParam(description = "运算符，枚举：add/subtract/multiply/divide") String op) {
    // normalize(op) 兼容 "+"/"加"/"plus" 等写法
    // divide 时 b==0 返回 {"code":"DIVIDE_BY_ZERO",...}
    // 正常返回 {"a":..,"b":..,"op":..,"result":..}
}
```

### TimeTool（时间）

要点：LLM 不知道"现在" → 交给 Tool；支持时区，非法时区兜底为系统默认。

```
@Tool(description = "获取当前的日期和时间。当用户询问现在几点、今天几号、当前时间时使用")
public String getCurrentTime(
        @ToolParam(description = "时区ID，如 Asia/Shanghai；可不填") String zone) {
    // 返回 {"time":"2026-07-03 15:30:00","zone":"Asia/Shanghai","weekday":"FRIDAY"}
}
```

### WeatherTool（天气，第四章已实现）

要点：模拟外部 API；返回结构化 JSON（真实项目替换为和风天气等）。

### 多工具挂载（Day3AgentService + Controller）

Service 里的通用方法支持传任意多个工具：

```
public String chat(String userMessage, Object... tools) {
    return chatClient.prompt().user(userMessage).tools(tools).call().content();
}
```

Controller 一次挂载三个：

```
@GetMapping("/agent")
public String agent(@RequestParam String msg) {
    return day3AgentService.chat(msg, weatherTool, timeTool, calculatorTool);
}
```

### 运行验证（离线学习可对照日志）

```
访问                                              预期触发的工具日志
------------------------------------------------  -----------------------------
/day3/agent?msg=现在几点？                          [TimeTool] 被调用
/day3/agent?msg=帮我算 1234 乘以 5678               [CalculatorTool] 被调用, op=multiply
/day3/agent?msg=杭州天气如何                         [WeatherTool] 被调用, city=杭州
/day3/agent?msg=杭州天气如何？顺便告诉我现在几点      [WeatherTool] + [TimeTool] 都被调用
```

看到对应日志，就证明 LLM 正确路由 / 一次多调成功。

<!-- CHAPTER5_PART2 -->

### 每个工具的完整调用流程（逐个拆解）

**CalculatorTool 调用流程**
```
用户"算 1234 乘 5678"
 → LLM 匹配 description"精确计算"→ 选 calculate
 → tool_calls: calculate(a=1234,b=5678,op="multiply")
 → Java 执行 normalize("multiply")→ 1234*5678=7006652
 → 返回 {"a":1234,"b":5678,"op":"multiply","result":7006652}
 → LLM 组织："1234 乘以 5678 等于 7006652。"
```

**TimeTool 调用流程**
```
用户"现在几点"
 → LLM 匹配 description"现在几点"→ 选 getCurrentTime
 → tool_calls: getCurrentTime(zone=null)
 → Java 执行 ZonedDateTime.now(系统时区)
 → 返回 {"time":"2026-07-03 15:30:00","zone":"Asia/Shanghai","weekday":"FRIDAY"}
 → LLM 组织："现在是 2026 年 7 月 3 日 15:30，星期五。"
```

**WeatherTool 调用流程**
```
用户"杭州天气"
 → LLM 匹配 description"天气"→ 选 getWeather
 → tool_calls: getWeather(city="杭州")
 → Java 执行 mockWeather("杭州")
 → 返回 {"city":"杭州","temp":22,"desc":"多云","humidity":60}
 → LLM 组织："杭州今天多云，22℃，湿度 60%。"
```

---

## ④ 用在哪（应用场景）

- **CalculatorTool 类**：金融/电商场景的价格计算、折扣计算、税费计算——凡涉及精确数字，一律 Tool 化，绝不让 LLM 心算。
- **TimeTool 类**：任何"相对时间"处理（"明天"、"下周三"）的第一步都是拿到"现在"，再由 LLM/工具推算。
- **WeatherTool 类**（外部 API 模式）：查快递、查股价、查库存、查天气……所有"实时外部数据"都套这个模式：`@Tool → 调外部 API → 返回结构化 JSON`。

### 真实案例：AI 办公助手

用户："帮我算下这个季度 3 个月的总销售额，分别是 120 万、95 万、138 万，再看看今天几号方便我写周报"

```
LLM 一次编排两个工具：
  calculate(120+95+138) → 353 万
  getCurrentTime()      → 2026-07-03
汇总："本季度总销售额 353 万元；今天是 2026 年 7 月 3 日。"
```

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **多个工具 description 语义重叠** → LLM 纠结选哪个。要让每个工具的"触发场景"互斥、清晰。
2. **参数类型用包装类可能为 null** → 基本类型 double 更安全（本例 Calculator 用 double）。
3. **枚举参数不做归一化** → LLM 传"乘"/"×"/"multiply" 都可能出现，必须 normalize。
4. **时间/时区硬编码** → 用 ZoneId 参数化，非法值兜底，别写死。
5. **一次挂载过多工具** → prompt 膨胀、选择变差。按场景分组挂载（第七章工具目录）。

### 性能与工程化优化

- **工具分组按需挂载**：客服场景挂订单类工具，办公场景挂日程类，避免全量挂载。
- **无依赖工具并行**：LLM 一次返回多个 tool_calls 时，Spring AI 可并行执行（省时）。
- **统一返回格式**：所有工具都用 `{...}` 或统一含 `code/message` 的错误结构，便于前端/日志处理。
- **可观测性**：每个工具入口打 log（本例都有 `[XxxTool] 被调用`），线上可统计各工具调用频次、耗时、失败率。

### 面试高频问题

- **Q：多个工具时 LLM 如何选对？**
  A：基于每个工具 name + description 与用户输入做语义匹配。描述清晰互斥则选得准，语义重叠会导致误选。
- **Q：LLM 能一次调多个工具吗？**
  A：能。若用户输入含多个独立意图，LLM 一轮可返回多个 tool_calls，框架分别执行后一起回传。
- **Q：为什么计算也要走工具，LLM 不能算吗？**
  A：LLM 是概率预测，大数运算易错。工具保证 100% 精确，这是可靠性底线。

### 最佳实践清单

- ✅ 每个工具单一职责，description 触发场景互斥清晰。
- ✅ 枚举参数做归一化，数值参数用基本类型。
- ✅ 统一返回结构（正常 JSON / error JSON）。
- ✅ 工具入口打日志；按场景分组挂载工具。

---

## 本章总结

- **一句话**：三个典型工具（算/时间/天气）覆盖了 LLM 的三大残疾，多工具靠 description 语义路由。
- **核心能力**：LLM 能自动选对工具，也能一次调多个工具（多意图并行）。
- **本章成果**：CalculatorTool / TimeTool / WeatherTool 全部实现并编译通过，`GET /day3/agent` 可验证多工具选择。

---

### 本章练习（离线自测）

1. 启动应用，依次访问四个 `/day3/agent?msg=...` 示例，核对控制台日志与预期工具是否一致。
2. 思考并尝试：给 CalculatorTool 再加一个"求平方根"能力，应该新增一个工具方法，还是在 calculate 里加分支？（提示：单一职责——新增独立方法更好。）

下一章（第六章）：**多个 Tool 协同工作（Weather + Email）**，讲"上一个工具的输出作为下一个工具的输入"，这就是 Workflow 的基础。

<!-- CHAPTER5_END -->

---

# 第六章：多个 Tool 协同工作（Weather + Email）

> 本章代码已实现并编译通过：
> - `day3funcall/tool/EmailTool.java`（写操作工具）
> - 协同入口：`day3funcall/controller/Day3Controller.java` 的 `GET /day3/workflow`

---

## ① 为什么学（核心价值）

第五章的多工具是"**并行独立**"：查天气和查时间互不相干，LLM 分别调用即可。

但真实业务往往是"**串行依赖**"：

> "查一下杭州天气，**把结果**发邮件给老板"

这里第二步（发邮件）依赖第一步（查天气）的输出。这就从"多工具选择"升级为"**多工具编排**"——也就是 **Workflow（工作流）的雏形**。

**一句话价值**：掌握工具协同，你就理解了 Agent 如何把"零散能力"串成"完整业务流程"，这是从"能调工具"到"能办事"的关键一跃。

---

## ② 是什么（概念与原理）

### 并行 vs 串行（两种协同）

```
并行（第五章）：意图之间无依赖
  用户"天气+时间" → [getWeather, getCurrentTime] 同时调 → 合并结果

串行（本章）：后一步依赖前一步输出
  用户"查天气并发邮件"
    → 第1步 getWeather("杭州") → {"temp":22,"desc":"多云"}
    → LLM 把天气组织成邮件正文
    → 第2步 sendEmail(to, subject, content="杭州今天多云22℃") → {"status":"SENT"}
```

### 核心原理：LLM 的多轮 Agent Loop

串行协同靠的是**多轮工具调用循环**（回顾你第二章画的图）：

```
用户："查杭州天气，把结果发邮件到 boss@example.com"
   │
   ▼ 第 1 轮
LLM 判断：先得知道天气 → tool_calls: getWeather("杭州")
   │
   ▼ Spring AI 执行 WeatherTool
返回 {"city":"杭州","temp":22,"desc":"多云","humidity":60}
   │
   ▼ 结果回传 LLM
   ▼ 第 2 轮
LLM 判断：现在有天气了，可以发邮件 → 组织正文
   tool_calls: sendEmail(
       to="boss@example.com",
       subject="杭州今日天气",
       content="杭州今天多云，气温22℃，湿度60%")
   │
   ▼ Spring AI 执行 EmailTool
返回 {"messageId":"msg_a1b2","status":"SENT"}
   │
   ▼ 结果回传 LLM
   ▼ 第 3 轮
LLM 判断：任务完成 → finish_reason=stop
   最终回答："已查询杭州天气（多云22℃）并发送邮件至 boss@example.com。"
```

**关键认知**：Spring AI 会自动执行这个多轮循环，你不需要手写"先调A再调B"的编排代码——**编排逻辑由 LLM 动态决定**。这正是 Agent 与传统硬编码 Workflow 的本质区别。

### Agent 编排 vs 传统 Workflow 编排

| 维度 | 传统 Workflow（如 Activiti/BPMN） | Agent 工具编排 |
|------|-----------------------------------|----------------|
| 流程定义 | 开发者硬编码 A→B→C | LLM 根据意图动态决定 |
| 灵活性 | 改流程要改代码 | 改需求只改 prompt/工具 |
| 适应性 | 只能走预设路径 | 能应对没预设过的组合 |
| 可控性 | 强（确定） | 较弱（需约束/校验） |

<!-- CHAPTER6_PART2 -->

---

## ③ 怎么用（实战演练）

### EmailTool（写操作工具，注意幂等与校验）

```
@Component
public class EmailTool {
    @Tool(description = "发送邮件给指定收件人。当用户需要把信息/通知/报告发送到某个邮箱时使用")
    public String sendEmail(
            @ToolParam(description = "收件人邮箱地址，如 user@example.com") String to,
            @ToolParam(description = "邮件主题") String subject,
            @ToolParam(description = "邮件正文内容") String content) {
        // 1. 参数校验：邮箱格式、主题非空
        if (to == null || !to.matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$"))
            return "{\"code\":\"INVALID_PARAM\",\"message\":\"收件人邮箱格式不正确\"}";
        // 2. 真实项目调 JavaMailSender；写操作要幂等，防 LLM 重复调用重复发送
        String messageId = "msg_" + UUID.randomUUID().toString().substring(0, 8);
        // 3. 返回结构化 JSON
        return "{\"messageId\":\"" + messageId + "\",\"status\":\"SENT\",...}";
    }
}
```

### 协同入口（同时挂 Weather + Email）

```
@GetMapping("/workflow")
public String workflow(@RequestParam String msg) {
    // 只需把两个工具都挂上，串行编排由 LLM 自动完成
    return day3AgentService.chat(msg, weatherTool, emailTool);
}
```

**注意**：你没有写任何"先查天气再发邮件"的顺序代码——顺序是 LLM 根据 `msg` 语义自己推断的。这就是 Agent 编排的魅力。

### 运行验证（离线对照日志）

```
访问:
  /day3/workflow?msg=查一下杭州天气，把结果发邮件到 boss@example.com

预期控制台日志（按顺序）:
  [Day3Agent] 收到用户输入: 查一下杭州天气...
  [WeatherTool] 被调用, city=杭州                    ← 第1轮
  [EmailTool] 被调用, to=boss@example.com, subject=... ← 第2轮
  [EmailTool] 邮件已发送(模拟), messageId=msg_xxxx
  [Day3Agent] 最终回答: 已查询杭州天气并发送邮件...

看到 WeatherTool 先、EmailTool 后，就证明串行协同成功。
```

### Python 对照（读开源用）

```
# LangGraph 里，串行协同通常用 StateGraph 显式定义节点与边，
# 或用 create_react_agent 让 LLM 自主决定调用顺序（类似 Spring AI 的自动循环）
from langgraph.prebuilt import create_react_agent
agent = create_react_agent(llm, tools=[get_weather, send_email])
agent.invoke({"messages": [("user", "查杭州天气并发邮件给 boss@example.com")]})
```

---

## ④ 用在哪（应用场景）

工具协同 = 把企业里"跨系统的操作流程"自动化：

| 业务流程 | 工具协同链 |
|---------|-----------|
| **AI 办公助手：写日报** | 查考勤 → 查任务完成度 → 汇总 → 发邮件给主管 |
| **AI 客服：自动退款** | 查订单 → 校验退款政策 → 发起退款 → 发通知短信 |
| **AI 运维：故障告警** | 查监控指标 → 判断阈值 → 查负责人 → 发告警到企业微信 |
| **AI 量化：条件下单** | 查行情 → 算指标 → 风控校验 → 下单 → 记录日志 |

### 案例拆解：AI 运维告警 Agent

```
用户/定时触发："检查订单服务的 CPU，超过 80% 就通知值班人"

Agent 编排（4 个工具串行）：
  ① queryMetric("order-service","cpu")  → {"cpu":85}
  ② （LLM 判断 85 > 80，需要告警）
  ③ queryOnCall("order-service")        → {"name":"张三","phone":"..."}
  ④ sendAlert(to=张三, msg="CPU 85% 超阈值") → {"status":"SENT"}
汇总："订单服务 CPU 达 85%，已告警值班人张三。"
```

**价值**：把人肉"看监控→找人→通知"的流程，压缩成一句话触发的自动化 Agent。

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **写操作工具不幂等** → LLM 可能重复调用 sendEmail，导致重复发送。用业务唯一键去重。
2. **依赖信息缺失时硬发** → 天气没查到就发空邮件。前一步失败（返回 error）时，LLM 应中止或追问，工具设计要能表达失败。
3. **让 LLM 编排关键交易流程** → 涉及资金/不可逆操作，纯靠 LLM 顺序不可靠，应加确定性校验/审批环节。
4. **正文由 LLM 自由生成不受控** → 可能夹带幻觉。关键通知建议用模板，工具接收字段而非整段正文。
5. **多轮循环无上限** → LLM 反复调工具停不下来。要设最大轮次上限，防止死循环烧 token。

### 性能与工程化优化

- **最大轮次限制**：给 Agent Loop 设 maxIterations（如 5 轮），超出强制结束。
- **写操作加确认**：高风险工具（转账/退款）在执行前插入人工确认或二次校验。
- **失败可恢复**：前置工具失败返回明确 error，让 LLM 能决策"重试/换路径/告知用户"。
- **审计日志**：串行链路每一步都记 traceId，串起来能还原"LLM 为什么这么编排"。
- **正文模板化**：通知类邮件用固定模板 + 变量填充，减少 LLM 自由发挥带来的风险。

### 面试高频问题

- **Q：多工具串行协同靠什么实现？**
  A：靠 LLM 的多轮 Agent Loop——每轮根据已有结果决定下一个工具，框架自动执行并回传，直到 finish_reason=stop。
- **Q：Agent 工具编排和传统 Workflow 引擎的区别？**
  A：传统 Workflow 流程硬编码、走固定路径；Agent 编排由 LLM 动态决定顺序，灵活但可控性较弱，关键流程需加确定性约束。
- **Q：写操作工具要注意什么？**
  A：幂等（防重复调用）、参数校验、失败返回结构化 error、高风险操作加确认，全链路审计日志。

### 最佳实践清单

- ✅ 写操作工具幂等 + 参数校验 + 失败返 error JSON。
- ✅ Agent Loop 设最大轮次上限，防死循环。
- ✅ 高风险操作加人工确认/二次校验。
- ✅ 通知正文模板化，关键字段结构化传参。
- ✅ 串行链路全程审计日志（traceId）。

---

## 本章总结

- **一句话**：多工具协同 = LLM 多轮 Agent Loop 动态编排，前一步输出喂给后一步，这就是 Workflow 雏形。
- **核心区别**：编排顺序由 LLM 动态决定（非硬编码），灵活但需约束。
- **本章成果**：EmailTool + `/day3/workflow` 已实现，可验证"查天气→发邮件"串行协同。

---

### 本章练习（离线自测）

1. 启动访问 `/day3/workflow?msg=查一下杭州天气，把结果发邮件到 boss@example.com`，确认日志中 WeatherTool 先于 EmailTool。
2. 思考：如果查天气失败（返回 error），你希望 LLM 怎么做？（提示：中止并告知用户，而不是发一封"天气未知"的邮件——这需要 EmailTool/编排层能感知前置失败。）

下一章（第七章）：**企业最佳实践** —— 如何管理几十上百个工具、工具目录设计、权限、日志、异常处理。

<!-- CHAPTER6_END -->

---

# 第七章：企业最佳实践

> 本章演示代码已实现并编译通过：
> - 工具目录：`day3funcall/registry/ToolRegistry.java`
> - 分组入口：`Day3Controller` 的 `GET /day3/group`、`GET /day3/tools`
> - 系统提示对话：`Day3AgentService.chatWithSystem(...)`

---

## ① 为什么学（核心价值）

前六章你有 4 个工具。但真实企业级 Agent（AI 客服、办公助手）动辄**几十上百个工具**。此时新问题爆发：

| 规模化痛点 | 具体表现 |
|-----------|---------|
| **工具太多，prompt 爆炸** | 100 个工具全塞给 LLM，token 巨贵，选择还变差 |
| **选择准确率下降** | 工具越多，语义越容易重叠，LLM 越容易选错 |
| **权限失控** | 谁都能调"退款""转账"工具，出事就是资损/合规事故 |
| **出问题查不到** | LLM 调错工具、传错参，没日志根本无法定位 |
| **异常炸穿** | 一个工具抛异常，整个 Agent 挂掉 |

**一句话价值**：这一章决定你的 Agent 能不能从"demo"走向"生产"。会写工具是入门，能管理上百个工具才是架构师。

---

## ② 是什么（概念与原理）

### 1. 工具目录（Tool Registry / Catalog）

核心思想：**不要把所有工具都丢给 LLM，按场景分组，按需挂载。**

```
                    ┌──────────────────────────┐
                    │      工具目录 Registry     │
                    ├──────────────────────────┤
   场景=assistant → │ [Weather, Time, Calc]     │ → 挂给"日常助手"Agent
   场景=office    → │ [Time, Email]             │ → 挂给"办公"Agent
   场景=finance   → │ [Stock, Order, Refund...] │ → 挂给"金融"Agent
                    └──────────────────────────┘
```

好处：每个场景只暴露相关的 5~10 个工具给 LLM，既省 token 又提升选择准确率。

### 2. 权限管理（谁能调哪个工具）

```
用户请求
   │ 携带身份/角色（如 普通客服 / 高级客服 / 管理员）
   ▼
权限过滤层：根据角色筛选"该用户被允许的工具子集"
   │  普通客服：可查订单，不可发起退款
   │  高级客服：可查订单 + 发起退款
   ▼
只把"有权限的工具"挂给 LLM
```

关键：**权限校验必须在工具执行前，不能指望 LLM 自觉。** LLM 是可被诱导的（prompt 注入），权限是硬边界。

### 3. 日志与可观测性

每次工具调用都应记录一条结构化日志：

```
traceId | 用户 | 工具名 | 入参 | 出参 | 耗时ms | 成功/失败 | 错误码
```

用途：审计（谁在什么时候做了什么）、排障（LLM 为什么选错）、优化（哪些工具最常用/最常失败）。

### 4. 异常处理（工具失败不能炸穿 Agent）

```
工具内部异常
   │
   ▼
捕获 → 转成结构化 error JSON 返回（而非抛出）
   │  {"code":"TIMEOUT","message":"天气服务超时"}
   ▼
LLM 收到 error → 优雅处理（告知用户/重试/换方案）
```

原则：**工具永远返回结果（正常 JSON 或 error JSON），永远不向上抛异常中断 Agent Loop。**

### 图解：企业级 Agent 工具治理架构

```
┌───────────────────────────────────────────────┐
│                用户请求(带身份)                   │
└───────────────────────┬───────────────────────┘
                       ▼
             ┌───────────────────┐
             │   权限过滤层        │  按角色筛选可用工具
             └─────────┬─────────┘
                       ▼
             ┌───────────────────┐
             │   工具目录 Registry │  按场景分组取用
             └─────────┬─────────┘
                       ▼
             ┌───────────────────┐
             │   LLM + ChatClient │  只见到"该场景+有权限"的工具
             └─────────┬─────────┘
                       ▼
             ┌───────────────────┐
             │   日志/审计切面     │  记录每次调用(traceId/入参/耗时)
             └─────────┬─────────┘
                       ▼
             ┌───────────────────┐
             │   工具执行 + 异常兜底│  失败转 error JSON, 不炸穿
             └───────────────────┘
```

<!-- CHAPTER7_PART2 -->

---

## ③ 怎么用（实战演练）

### 工具目录（ToolRegistry）

按场景分组，编排层按 group 取用：

```
@Component
@RequiredArgsConstructor
public class ToolRegistry {
    private final WeatherTool weatherTool;
    private final TimeTool timeTool;
    private final CalculatorTool calculatorTool;
    private final EmailTool emailTool;

    private final Map<String, List<Object>> groups = new LinkedHashMap<>();

    @PostConstruct
    public void init() {
        groups.put("assistant", List.of(weatherTool, timeTool, calculatorTool));
        groups.put("office",    List.of(timeTool, emailTool));
        groups.put("all",       List.of(weatherTool, timeTool, calculatorTool, emailTool));
    }

    public Object[] getToolsByGroup(String group) {
        return groups.getOrDefault(group, groups.get("assistant")).toArray();
    }
}
```

### 分组挂载入口（Controller）

```
@GetMapping("/group")
public String group(@RequestParam String group, @RequestParam String msg) {
    // 只挂载该场景相关的工具，而不是全部
    return day3AgentService.chat(msg, toolRegistry.getToolsByGroup(group));
}
```

### 系统提示词（约束 Agent 行为）

```
public String chatWithSystem(String systemPrompt, String userMessage, Object... tools) {
    return chatClient.prompt()
            .system(systemPrompt)   // ← 设定人设/边界，如"只回答业务问题，不得泄露内部信息"
            .user(userMessage)
            .tools(tools)
            .call().content();
}
```

### 权限过滤（伪代码思路）

```
// 真实项目：从当前登录用户拿角色，筛选可用工具
List<Object> allowedTools = registry.getToolsByGroup(scene).stream()
        .filter(tool -> permissionService.canUse(currentUser, toolName(tool)))
        .toList();
chatClient.prompt().user(msg).tools(allowedTools.toArray()).call();
```

### 日志切面（AOP 思路）

```
// 用 @Aspect 环绕所有 @Tool 方法，统一记录 traceId/入参/耗时/结果
@Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
public Object logTool(ProceedingJoinPoint pjp) throws Throwable {
    long t = System.currentTimeMillis();
    try {
        Object r = pjp.proceed();
        log.info("[TOOL] {} args={} cost={}ms ok", pjp.getSignature().getName(),
                 Arrays.toString(pjp.getArgs()), System.currentTimeMillis()-t);
        return r;
    } catch (Exception e) {
        log.error("[TOOL] {} failed", pjp.getSignature().getName(), e);
        return "{\"code\":\"TOOL_ERROR\",\"message\":\"工具执行异常\"}"; // 不炸穿
    }
}
```

### 运行验证

```
查看工具目录:   GET /day3/tools           → {"assistant":3,"office":2,"all":4}
按场景挂载:     GET /day3/group?group=office&msg=把"周会10点"发到 a@b.com
                → 日志显示 [ToolRegistry] 取用分组=office, 工具数=2
```

---

## ④ 用在哪（应用场景）

### 大型 AI 客服平台（上百工具的治理）

```
工具目录按业务域分组：
  订单域:   queryOrder / cancelOrder / modifyAddress ...
  售后域:   applyRefund / queryRefund / uploadEvidence ...
  会员域:   queryPoints / queryLevel / queryCoupon ...
  ...共 120+ 工具

一次会话按"用户问题分类"只挂载对应域的 8~12 个工具：
  用户问退款 → 只挂"售后域"工具 → LLM 选择又快又准

权限：
  普通用户 Agent：只读工具（query*）
  客服坐席 Agent：读 + 部分写（applyRefund）
  管理员 Agent：全部（含 forceRefund）
```

**价值**：这套治理让 Agent 在上百工具下依然稳定、安全、可审计——这正是商业化 Agent 的工程底座。

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **全量挂载工具** → prompt 爆炸、选择变差、成本飙升。必须按场景分组。
2. **靠 LLM 做权限判断** → 可被 prompt 注入绕过。权限必须是代码硬边界，在执行前校验。
3. **工具抛异常** → 中断 Agent Loop，整个会话崩。统一捕获转 error JSON。
4. **无 traceId** → 分布式下无法串联一次会话的多次工具调用，排障困难。
5. **description 大量重叠** → 工具多时 LLM 频繁选错。定期审查、去重、明确边界。

### 性能与工程化优化

- **分组 + 动态挂载**：按意图分类先粗筛场景，再挂该场景工具（可用一个轻量分类模型/规则）。
- **工具级限流/熔断**：外部依赖型工具加限流、熔断，防止被 LLM 高频调用打垮下游。
- **结果缓存**：只读、幂等工具的结果可短期缓存（如天气 5 分钟）。
- **统一超时**：所有工具设合理超时，超时返回 error JSON。
- **指标监控**：统计各工具调用量/成功率/P99 耗时，接入 Grafana 等看板。
- **灰度与版本**：工具升级用新增字段而非改字段；新工具灰度放量观察选择准确率。

### 面试高频问题

- **Q：几十上百个工具怎么管理？**
  A：建工具目录（Registry）按业务域/场景分组，每次会话只挂载相关子集，避免 prompt 膨胀与选择退化。
- **Q：工具权限怎么控制？**
  A：在工具执行前按用户角色做硬校验（代码层），绝不依赖 LLM 自觉，防 prompt 注入绕过。
- **Q：工具异常怎么处理才不影响 Agent？**
  A：工具内部捕获异常并返回结构化 error JSON，永不向上抛，保证 Agent Loop 能继续/优雅收尾。
- **Q：怎么排查 LLM 为什么调错工具？**
  A：全链路 traceId + 结构化日志（工具名/入参/结果/耗时），复盘调用轨迹与 description 匹配情况。

### 最佳实践清单

- ✅ 工具目录按场景分组，按需挂载（非全量）。
- ✅ 权限在执行前硬校验，不信任 LLM。
- ✅ 工具异常统一转 error JSON，不炸穿 Agent。
- ✅ 全链路 traceId + 结构化日志 + 指标监控。
- ✅ 外部依赖工具加超时/限流/熔断；只读工具可缓存。
- ✅ 系统提示词约束 Agent 行为边界。

---

## 本章总结

- **一句话**：会写工具是入门，能治理上百工具（分组/权限/日志/异常）才是架构师。
- **四大支柱**：工具目录（分组按需挂载）、权限（执行前硬校验）、可观测（traceId 日志）、异常兜底（转 error JSON）。
- **本章成果**：ToolRegistry + 分组入口 + 系统提示对话已实现，演示了工具治理的核心骨架。

---

### 本章练习（离线自测）

1. 访问 `GET /day3/tools` 查看工具目录分组；再访问 `GET /day3/group?group=office&msg=...` 观察只挂载了 2 个工具的日志。
2. 思考：你要做一个"AI 财务助手"，会怎么划分工具分组？哪些工具需要管理员权限？（提示：查询类可开放，转账/报销审批类需权限 + 二次确认。）

下一章（第八章，收官）：**完成 Agent Assistant V1** —— 整合天气/时间/计算三工具，做一个能自动选择工具的完整 Agent，并对你的代码做企业级点评与优化建议。

<!-- CHAPTER7_END -->

---

# 第八章：完成 Agent Assistant V1（收官）

> 本章成果已实现并编译通过：
> - 完整 Agent：`day3funcall/assistant/AgentAssistantV1.java`
> - 入口：`Day3Controller` 的 `GET /day3/assistant`

---

## ① 为什么学（核心价值）

前七章你学了：Tool 概念 → Function Calling 原理 → Tool 设计 → Spring AI 用法 → 三个工具 → 多工具协同 → 企业治理。

**但零散的知识点不等于会做产品。** 本章把它们**整合成一个完整、可运行、带人设、能自动选工具、有异常兜底的 Agent**——这就是你的第一个 Agent 作品 "Agent Assistant V1"。

**一句话价值**：这是从"学会零件"到"造出整机"的临门一脚，也是你 Agent 作品集的第一个 demo。

---

## ② 是什么（概念与原理）

### Agent Assistant V1 的能力边界

```
        ┌──────────────────────────────┐
        │      Agent Assistant V1        │
        │         （人设：小智）          │
        ├──────────────────────────────┤
        │  能力:                         │
        │   · 查天气   → WeatherTool     │
        │   · 查时间   → TimeTool        │
        │   · 计算     → CalculatorTool  │
        │  边界:                         │
        │   · 超范围(订机票)→ 礼貌拒绝    │
        │  兜底:                         │
        │   · 出异常   → 友好提示         │
        └──────────────────────────────┘
```

### 一次完整请求的内部流转

```
用户："北京天气如何？现在几点？再帮我算 88 乘以 9"
   │
   ▼
AgentAssistantV1.ask()
   │  chatClient.prompt()
   │    .system(人设+规则)          ← 设定"小智"身份与行为边界
   │    .user(用户问题)
   │    .tools(weather, time, calc) ← 挂载三工具
   │    .call()
   ▼
┌────────── Spring AI 自动 Agent Loop ──────────┐
│  LLM 识别出 3 个意图 → 一次返回 3 个 tool_calls: │
│    getWeather("北京"), getCurrentTime(), calculate(88,9,multiply)
│  Spring AI 并行/顺序执行三工具，结果全部回传     │
│  LLM 综合三个结果 → 组织成一段自然语言           │
└────────────────────────────────────────────────┘
   │
   ▼
"北京今天晴 26℃；现在 15:30；88 乘以 9 等于 792。有什么还能帮你的吗？"
```

### 关键设计：system prompt 的作用

system prompt 是 Agent 的"岗位说明书"，它决定：
- **人设**：名字、语气（小智、友好、口语化）
- **行为规则**：必须调工具、不臆测、超范围礼貌拒绝
- **输出风格**：简洁、中文

这是把"通用 LLM"约束成"特定业务 Agent"的关键手段。

<!-- CHAPTER8_PART2 -->

---

## ③ 怎么用（实战演练）

### Java 完整代码（AgentAssistantV1）

```
@Service
public class AgentAssistantV1 {
    private final ChatClient chatClient;
    private final WeatherTool weatherTool;
    private final TimeTool timeTool;
    private final CalculatorTool calculatorTool;

    private static final String SYSTEM_PROMPT = """
            你是一个专业、友好的生活助理 Agent，名叫"小智"。
            你可以帮用户查询天气、查询当前时间、进行数学计算。
            规则：
            1. 需要实时数据或精确计算时，必须调用相应工具，不要臆测。
            2. 超出能力范围（如订机票）礼貌说明做不到。
            3. 回答简洁、口语化，用中文。
            """;

    public AgentAssistantV1(ChatModel chatModel, WeatherTool w, TimeTool t, CalculatorTool c) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.weatherTool = w; this.timeTool = t; this.calculatorTool = c;
    }

    public String ask(String userMessage) {
        try {
            return chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .tools(weatherTool, timeTool, calculatorTool)
                    .call().content();
        } catch (Exception e) {
            log.error("处理失败", e);
            return "抱歉，我暂时遇到点问题，请稍后再试～";  // 异常兜底
        }
    }
}
```

入口：

```
@GetMapping("/assistant")
public String assistant(@RequestParam String msg) {
    return agentAssistantV1.ask(msg);
}
```

### Python 简单实现（对照理解）

```
from langchain_openai import ChatOpenAI
from langchain_core.tools import tool
from langchain.agents import create_tool_calling_agent, AgentExecutor
from langchain_core.prompts import ChatPromptTemplate

@tool
def get_weather(city: str) -> str:
    """查询城市实时天气"""
    return '{"city":"%s","temp":26,"desc":"晴"}' % city

@tool
def get_time() -> str:
    """获取当前时间"""
    from datetime import datetime
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")

@tool
def calculate(a: float, b: float, op: str) -> str:
    """四则运算 op=add/subtract/multiply/divide"""
    return str({"add":a+b,"subtract":a-b,"multiply":a*b,"divide":a/b}[op])

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是友好的助理小智，需要实时数据或计算时必须调用工具。"),
    ("human", "{input}"),
    ("placeholder", "{agent_scratchpad}"),
])
llm = ChatOpenAI(model="gpt-4o")
agent = create_tool_calling_agent(llm, [get_weather, get_time, calculate], prompt)
executor = AgentExecutor(agent=agent, tools=[get_weather, get_time, calculate])
print(executor.invoke({"input": "北京天气？现在几点？算下 88 乘以 9"}))
```

**对比**：Java(Spring AI) 的 `.call()` 自动跑 Agent Loop；Python(LangChain) 用 `AgentExecutor` 承担同样的循环角色。核心机制完全一致。

### 运行验证

```
GET /day3/assistant?msg=北京今天天气如何？现在几点？再帮我算 88 乘以 9

日志（三工具都触发）：
  [AgentAssistantV1] 用户: 北京今天天气如何...
  [WeatherTool] 被调用, city=北京
  [TimeTool] 被调用
  [CalculatorTool] 被调用, op=multiply
  [AgentAssistantV1] 小智: 北京今天...；现在...；88乘以9等于792。

超范围测试：
GET /day3/assistant?msg=帮我订一张明天去上海的机票
  → 小智礼貌说明："抱歉，我目前只能查天气/时间和做计算，订机票还做不到～"
```

---

## ④ 用在哪（应用场景）

Agent Assistant V1 的架构就是**所有单 Agent 产品的通用骨架**：

```
system prompt(人设/边界) + 一组工具(能力) + 自动 Loop(编排) + 异常兜底(健壮)
```

把工具换一换，就是不同产品：
- 换成 订单/物流/退款工具 → **AI 客服**
- 换成 日程/邮件/考勤工具 → **AI 办公助手**
- 换成 检索/文档工具 → **AI 知识库问答**
- 换成 行情/指标/下单工具 → **AI 量化助手**

**这就是你学完 Day3 的核心收获：掌握了一套可复用的 Agent 骨架。**

---

## ⑤ 避坑与优化（进阶提升）+��码点评

### 对本章 V1 代码的企业级点评

**做得好的（已达 demo 级规范）**：
- ✅ system prompt 设定人设与行为边界
- ✅ 三工具挂载，LLM 自动选择/编排
- ✅ 统一入口 `ask()` + 异常兜底（对外始终有回复）
- ✅ 关键节点打日志（用户输入/最终回答/各工具）
- ✅ 工具返回结构化 JSON、参数校验、枚举归一化

**距离生产级还差的（V2 优化方向）**：

| 优化项 | 现状(V1) | 生产级(V2) |
|--------|---------|-----------|
| **多轮记忆** | 无（每次独立） | 加 ChatMemory，支持上下文对话 |
| **工具治理** | 硬编码挂三工具 | 接 ToolRegistry 按场景/权限动态挂载 |
| **可观测** | 简单 log | traceId 全链路 + 指标监控 |
| **Loop 上限** | 依赖框架默认 | 显式设 maxIterations 防死循环 |
| **超时/降级** | 工具内简单处理 | 统一超时 + 熔断 + 降级话术 |
| **安全** | 无 | prompt 注入防护 + 敏感操作二次确认 |
| **流式输出** | 一次性返回 | SSE 流式，提升体验 |

### 常见错误

1. **忘记异常兜底** → 工具/模型异常直接 500 给用户。必须 try-catch 返回友好话术。
2. **system prompt 太弱** → Agent 乱答/不调工具。规则要明确"何时必须调工具"。
3. **无能力边界声明** → 用户问超范围问题，Agent 幻觉硬答。要在 prompt 里声明边界。
4. **无多轮记忆却做多轮场景** → 用户说"那上海呢"，Agent 不知道在问天气。需要 ChatMemory。

### 面试高频问题

- **Q：一个完整的单 Agent 由哪几部分组成？**
  A：system prompt（人设/边界）+ 工具集（能力）+ Agent Loop（LLM 自动编排）+ 异常兜底（健壮性），可选记忆与可观测。
- **Q：怎么防止 Agent 回答超出能力范围的问题？**
  A：在 system prompt 声明能力边界 + 规则；工具只提供确定能力；必要时加输出校验。
- **Q：V1 到生产级还要补什么？**
  A：多轮记忆、工具治理（目录/权限）、全链路可观测、Loop 上限、超时熔断、安全防护、流式输出。

### 最佳实践清单

- ✅ system prompt 明确人设、规则、能力边界。
- ✅ 统一入口 + 异常兜底，对外永远有友好回复。
- ✅ 工具返回结构化 JSON，LLM 只负责表达。
- ✅ 关键节点日志；生产加 traceId 与监控。
- ✅ 从 V1 迭代到 V2：记忆、治理、可观测、安全逐步补齐。

---

## 本章总结

- **一句话**：Agent Assistant V1 = system prompt + 工具集 + 自动 Loop + 异常兜底，是所有单 Agent 产品的通用骨架。
- **本章成果**：一个可运行、带人设、能自动选三工具、有兜底的完整 Agent（`/day3/assistant`）。
- **迭代方向**：V2 补齐记忆、工具治理、可观测、安全、流式。

---

# Day3 总收官

恭喜你完成 Day3！你已经掌握：

```
第一章  为什么需要 Tool        → LLM 决策、程序执行
第二章  Function Calling 原理  → 三阶段协议 + Agent Loop
第三章  Tool 设计原则          → 返回 JSON / 单一职责 / 六要素
第四章  Spring AI Tool Calling → @Tool + ChatClient
第五章  三个 Tool              → 多工具语义路由
第六章  多 Tool 协同           → 串行编排 = Workflow 雏形
第七章  企业最佳实践           → 目录/权限/日志/异常四�柱
第八章  Agent Assistant V1     → 整合成完整 Agent 作品
```

**代码资产（本包，全部编译通过）**：
- 工具：`WeatherTool` / `TimeTool` / `CalculatorTool` / `EmailTool`
- 服务：`Day3AgentService`（单/多/系统提示对话）
- 治理：`ToolRegistry`（工具目录分组）
- 作品：`AgentAssistantV1`（收官 Agent）
- 入口：`Day3Controller`（weather/agent/workflow/group/tools/assistant）

**测试入口一览**：
```
/day3/weather   单工具
/day3/agent     多工具自动选择
/day3/workflow  Weather+Email 协同
/day3/group     按工具目录分组挂载
/day3/tools     查看工具目录
/day3/assistant Agent Assistant V1（收官）
```

### 结课练习

1. 跑通 `/day3/assistant`，测试三种情况：单意图、多意图、超范围。
2. 挑战：给小智加一个新工具（如"汇率查询"），体验"只加一个 @Tool 方法就扩展 Agent 能力"的快感。
3. 进阶思考：如果要做成商业化产品，你会优先补 V2 的哪一项？（提示：多轮记忆通常是体验的第一痛点。）

> 下一步学习建议：Day4 可深入 **ChatMemory（多轮记忆）** 或 **RAG（知识库检索）**，它们都建立在你今天掌握的 Tool 能力之上。

<!-- CHAPTER8_END -->