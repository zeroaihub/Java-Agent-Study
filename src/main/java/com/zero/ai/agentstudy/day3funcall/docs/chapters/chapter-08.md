# 第八章：完成 Agent Assistant V1（收官）

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day3 · 第八章
>
> 教学框架（五段式）：① 为什么学 → ② 是什么 → ③ 怎么用 → ④ 用在哪 → ⑤ 避坑与优化

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

---

## ③ 怎么用（实战演练）

### Java 完整代码（AgentAssistantV1）

```java
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

```java
@GetMapping("/assistant")
public String assistant(@RequestParam String msg) {
    return agentAssistantV1.ask(msg);
}
```

### Python 简单实现（对照理解）

```python
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

## ⑤ 避坑与优化（进阶提升）+ 代码点评

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
  A：system prompt（人设/边界）+ 工具集（能力）+ Agent Loop（LLM自动编排）+ 异常兜底（健壮性），可选记忆与可观测。
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
第七章  企业最佳实践           → 目录/权限/日志/异常四大支柱
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