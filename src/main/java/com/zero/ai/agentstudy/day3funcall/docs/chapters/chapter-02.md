# 第二章：Function Calling 工作原理

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day3 · 第二章
>
> 教学框架（五段式）：① 为什么学 → ② 是什么 → ③ 怎么用 → ④ 用在哪 → ⑤ 避坑与优化

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

## 本章练习

请画出 **Function Calling 完整调用流程**（从用户提问到最终回答，标出三阶段 + 关键字段）。

> 提示：用 ASCII 或文字描述均可，关键是把 `tools` 数组、`tool_calls`、`finish_reason`、`role=tool` 这几个要素串起来。

---

> 下一章 → [第三章：Tool 设计原则](chapter-03.md)（为什么 Tool 必须返回 JSON、单一职责、企业设计规范）