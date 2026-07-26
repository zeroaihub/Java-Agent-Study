# 第八章 MCP Agent V1：把所有零件总装成一个会「思考-行动」的 Agent

> 本章配套可运行代码：`day07mcp/service/McpAgentService.java`、`day07mcp/controller/McpAgentController.java`、`day07mcp/dto/McpAgentResponse.java`
> 前置章节：第二~七章（协议、传输、客户端、工具、编排、最佳实践）

---

## 一、为什么要学（Why）

到这里，我们已经手里攥着一堆「零件」：

- 协议报文（`JsonRpcRequest`/`JsonRpcResponse`）——第二章；
- 传输层（`Transport`/`InProcessTransport`）——第三章；
- 客户端（`McpClient`：`initialize`/`listTools`/`callTool`）——第四章；
- 工具服务端（`WeatherTool`/`TimeTool`/`CalculatorTool`）——第五章；
- 编排引擎（`Workflow`）——第六章。

**但零件不是产品。** 用户不会关心 JSON-RPC、不会手动填工具名和参数。他们只想说一句「北京天气怎么样」或「帮我算 12 乘 8」，然后得到答案。

**Agent** 就是那个「翻译官 + 调度员」：它听懂人话（自然语言），自己判断该用哪个工具、怎么填参数，调用后再把结果翻译成人话回给用户。本章的 `McpAgentService` 就是把前七章所有零件**总装**成这样一个「MCP Agent V1」。

它要达成的四个能力（也是本次训练营的最终目标）：
1. **自动发现工具**——启动时问 Server「你有哪些工具」，不硬编码；
2. **自动调用工具**——根据用户意图选工具、填参、调用；
3. **新增工具无需改 Agent 代码**——工具是 Server 侧的事，Agent 动态感知；
4. **记录完整调用链日志**——每一步都可追踪。

---

## 二、是什么（What）——Agent 的最小闭环

一个 Agent 的经典循环是 **感知 → 决策 → 行动 → 回复**。我们的 V1 严格对应到代码：

| 阶段 | 做什么 | 代码位置 |
| --- | --- | --- |
| 感知 Perceive | 启动时 `listTools` 发现有哪些工具并缓存 | `@PostConstruct discoverTools()` |
| 决策 Decide | 根据用户输入识别意图、选工具、抽参数 | `chat()` + `handleXxx()` |
| 行动 Act | 通过 `McpClient.callTool` 调用工具 | `handleXxx()` 里的 `callTool` |
| 回复 Respond | 把工具结果整理成自然语言答案 | `toResponse()` |

> V1 的「决策」用的是**关键词规则 + 正则抽参**这种轻量方案。真实生产会换成 **LLM 的 Function Calling**——但请注意：**换的只是「决策」这一块，感知/行动/回复的骨架完全不变**。这正是 MCP 架构的威力——把「大脑（决策）」和「手脚（工具调用）」解耦了。

### 2.1 三个 HTTP 端点（`McpAgentController`）

| 端点 | 方法 | 作用 | 对应 MCP 动作 |
| --- | --- | --- | --- |
| `/api/mcp/tools` | GET | 查看 Agent 发现的工具清单 | tools/list |
| `/api/mcp/call` | POST | 直接指定工具名调用（绕过意图识别） | tools/call |
| `/api/mcp/chat?message=...` | GET | 自然语言对话，Agent 自动选工具 | 完整闭环 |

`/call` 是「裸调用」，用于验证底层链路；`/chat` 才是真正的 Agent 闭环。

---

## 三、怎么用（How）——源码逐段精讲

### 3.1 感知：启动即自动发现工具

```java
@Slf4j @Service
public class McpAgentService {
    private final McpClient mcpClient;
    private List<ToolDefinition> availableTools;   // 缓存发现到的工具

    public McpAgentService(McpClient mcpClient) { this.mcpClient = mcpClient; }

    @PostConstruct
    public void discoverTools() {
        try {
            mcpClient.initialize();                       // 握手
            this.availableTools = mcpClient.listTools();  // 发现工具
            log.info("[McpAgentService] Agent 启动，已发现工具: {}",
                    availableTools.stream().map(ToolDefinition::getName).toList());
        } catch (Exception e) {
            log.error("[McpAgentService] 工具发现失败", e);
        }
    }
}
```

要点：
- **`@PostConstruct` = 应用启动即感知**。Agent 一上线就问清「Server 有哪些工具」并缓存。
- **这就是「新增工具无需改 Agent」的根基**：Server 侧加一个工具，Agent 重启后 `listTools` 自动就能看到，`chat` 决策层再补一条意图规则即可（决策层是唯一需要动的地方，且不改协议/调用骨架）。
- 用 `availableTools` 缓存，供 `/tools` 端点查询，也可用于让 LLM 看到「可用工具清单」做决策。

### 3.2 决策：意图识别 + 参数抽取

```java
public McpAgentResponse chat(String userInput) {
    if (userInput == null || userInput.trim().isEmpty()) {
        return McpAgentResponse.fail("输入不能为空");
    }
    String input = userInput.trim();
    try {
        // 1) 计算意图：命中「数字 运算符 数字」或包含"算/计算"
        Matcher m = CALC_PATTERN.matcher(input);
        if (input.contains("算") || input.contains("计算") || m.find()) {
            return handleCalculate(input);
        }
        // 2) 时间意图
        if (input.contains("时间") || input.contains("几点") || input.contains("日期")) {
            return handleTime(input);
        }
        // 3) 天气意图
        if (input.contains("天气") || input.contains("气温") || input.contains("下雨")) {
            return handleWeather(input);
        }
        // 4) 兜底：无法匹配任何工具
        return McpAgentResponse.builder()
                .success(true).toolUsed(null)
                .answer("我暂时无法理解你的需求。可用能力：查天气、查时间、做四则运算。")
                .message("no-tool-matched").build();
    } catch (Exception e) {
        log.error("[McpAgentService] 处理失败", e);
        return McpAgentResponse.fail("处理异常: " + e.getMessage());
    }
}
```

要点：
- **意图识别是「决策」的核心**。V1 用关键词 + 正则（`CALC_PATTERN` 匹配 `12 + 8` 这类表达式）。命中哪个意图，就转给对应的 `handleXxx`。
- **有兜底分支**：识别不了就友好告知「我能做什么」，而不是报错——这是良好的用户体验。
- **异常被捕获**（注：如第七章所说，生产环境应改为分层异常 + 全局处理器，不直接返回 `e.getMessage()`）。

### 3.3 行动：抽参 + 调用工具（以计算为例）

```java
private McpAgentResponse handleCalculate(String input) {
    Matcher m = CALC_PATTERN.matcher(input);
    if (!m.find()) return McpAgentResponse.fail("没有识别到可计算的表达式，请输入如「12 + 8」");
    double a = Double.parseDouble(m.group(1));
    String opSymbol = m.group(2);
    double b = Double.parseDouble(m.group(3));
    String op = switch (opSymbol) {                 // 符号 → 工具枚举值
        case "+" -> "add";
        case "-" -> "subtract";
        case "*", "x", "X", "×" -> "multiply";
        case "/", "÷" -> "divide";
        default -> null;
    };
    if (op == null) return McpAgentResponse.fail("不支持的运算符: " + opSymbol);
    Map<String, Object> args = new HashMap<>();
    args.put("op", op); args.put("a", a); args.put("b", b);
    CallToolResult result = mcpClient.callTool("calculate", args);  // 行动！
    return toResponse("calculate", result);
}
```

要点：
- **抽参**：正则把 `12 * 8` 拆成 `a=12`、`opSymbol=*`、`b=8`，再把用户写的符号 `*` 映射成 `calculate` 工具约定的枚举 `multiply`——这一步正好对应第五章讲的 `CalculatorTool` 的 `op` 枚举约束。
- **行动**：拼好 `args` 后调 `mcpClient.callTool("calculate", args)`，Agent 完全不碰 JSON-RPC，全部交给第四章的 `McpClient`。
- `handleTime`（识别纽约/伦敦/东京 → 时区）、`handleWeather`（词典抽城市名）同理，只是抽参逻辑不同。

### 3.4 回复：区分「业务失败」与「成功」

```java
private McpAgentResponse toResponse(String toolName, CallToolResult result) {
    if (result.isError()) {
        // 工具业务失败（如除零、城市不存在）：如实告知用户
        return McpAgentResponse.builder()
                .success(false).toolUsed(toolName)
                .answer(result.asText())
                .message("tool-business-error").build();
    }
    return McpAgentResponse.ok(toolName, result.asText());
}
```

要点：
- **这里把第五章的「两种错误分层」落到了 Agent 的回复上**：`CallToolResult.isError()` 为 true（业务失败，如「除数不能为 0」），Agent 就把失败原因如实告诉用户，`success=false`；否则返回工具产出的正常文本。
- 注意区分：这里的 `isError` 是**工具业务失败**（正常响应里带的标志），跟协议级错误（`McpClient` 直接抛异常、被 `chat` 的 `catch` 兜住）是两条不同的路径。

### 3.5 对外暴露：`McpAgentController`

```java
@RestController @RequestMapping("/api/mcp")
public class McpAgentController {
    private final McpAgentService agentService;
    private final McpClient mcpClient;

    @GetMapping("/tools")                          // 看工具清单
    public List<ToolDefinition> tools() { return agentService.getAvailableTools(); }

    @PostMapping("/call")                          // 裸调用（验证底层链路）
    public CallToolResult call(@RequestBody McpCallRequest request) {
        return mcpClient.callTool(request.getToolName(), request.getArguments());
    }

    @GetMapping("/chat")                           // Agent 完整闭环
    public McpAgentResponse chat(@RequestParam("message") String message) {
        return agentService.chat(message);
    }
}
```

### 3.6 亲手验证完整闭环

启动应用后（记得用 JDK 17），依次访问：

```
# 1) 看 Agent 发现了哪些工具（感知）
GET  http://localhost:8080/api/mcp/tools

# 2) 裸调用某工具（验证底层 tools/call）
POST http://localhost:8080/api/mcp/call
     { "toolName": "get_weather", "arguments": { "city": "北京" } }

# 3) 自然语言对话（完整 Agent 闭环）
GET  http://localhost:8080/api/mcp/chat?message=北京天气怎么样
GET  http://localhost:8080/api/mcp/chat?message=帮我算 12 乘 8
GET  http://localhost:8080/api/mcp/chat?message=现在几点
GET  http://localhost:8080/api/mcp/chat?message=100 除以 0     # 触发业务失败
```

看控制台日志，你会看到「发现工具 → 收到输入 → 调用工具 → 完成」的完整调用链——这就是**目标里要求的「记录完整调用链日志」**。

---

## 四、用在哪（Where）

| 场景 | Agent V1 骨架的价值 |
| --- | --- |
| 智能客服 | 把「查订单/查物流/退款」做成工具，Agent 按意图自动调 |
| 内部效率工具 | 自然语言驱动查数据、跑脚本，无需记命令 |
| 多工具 Copilot | 决策层换成 LLM Function Calling，工具随时增减 |
| 与工作流结合 | 复杂任务由第六章的 Workflow 编排多个工具，Agent 负责入口决策 |

**演进路线**：V1（关键词决策）→ V2（LLM Function Calling 决策，把 `availableTools` 喂给模型）→ V3（多轮对话 + 记忆 + 工作流编排）。**每次升级只动「决策」层，感知/行动/回复的骨架和 MCP 协议层都不变**——这就是我们从第二章一路搭到这里的架构红利。

---

## 五、避坑与优化

| 坑 | 后果 | 正确做法 |
| --- | --- | --- |
| Agent 硬编码工具名/参数 | 加工具要改 Agent、耦合严重 | 靠 `listTools` 动态发现，决策层与工具解耦 |
| 意图识别无兜底 | 识别不了就报错，体验差 | 兜底分支友好提示「我能做什么」 |
| 把业务失败当协议错误抛 | 用户看到冷冰冰的异常 | `isError` 走正常回复（success=false + 原因） |
| 抽参不校验就调工具 | 触发工具报错、浪费一次调用 | 决策层先校验（如运算符是否支持）再调 |
| `catch(Exception)` 返回原始 message | 泄露内部细节 | 参考第七章：分层异常 + 全局处理器 |
| 关键词规则堆成大 if | 意图多了难维护 | 演进到 LLM Function Calling / 策略表驱动 |

---

## 六、核心知识速记

- Agent = **感知 → 决策 → 行动 → 回复** 的最小闭环。
- **感知**：`@PostConstruct` + `listTools` 动态发现工具并缓存。
- **决策**：意图识别 + 抽参（V1 用关键词/正则，可平滑升级为 LLM Function Calling）。
- **行动**：`McpClient.callTool`，Agent 不碰协议细节。
- **回复**：`toResponse` 区分业务失败（`isError`）与成功——延续第五章错误分层。
- **新增工具无需改 Agent**：工具在 Server 侧，Agent 靠 `listTools` 感知；升级只动决策层。
- 三端点：`/tools`（看）、`/call`（裸调）、`/chat`（闭环）。

---

## 七、常见面试题

**Q1：一个最小 Agent 的闭环包含哪几步？分别对应本项目哪段代码？**
A：感知（`@PostConstruct discoverTools` 调 `listTools` 发现工具）→ 决策（`chat` 意图识别 + `handleXxx` 抽参）→ 行动（`mcpClient.callTool`）→ 回复（`toResponse` 整理成自然语言并区分成败）。

**Q2：为什么说「新增一个工具不需要改 Agent 代码」？**
A：因为工具的定义、schema、执行逻辑全在 Server 侧；Agent 通过 `listTools` 动态发现，不硬编码工具清单。Server 加工具后 Agent 重启即感知。唯一可能要动的是「决策层」新增一条意图规则——若决策换成 LLM Function Calling，把 `availableTools` 喂给模型，则连决策层都无需手改。

**Q3：`/chat` 和 `/call` 两个端点有什么区别？**
A：`/call` 是「裸调用」，由调用方直接指定 `toolName` 和 `arguments`，绕过意图识别，用于验证底层 `tools/call` 链路；`/chat` 是完整 Agent 闭环，输入自然语言，由 Agent 自动完成意图识别、选工具、抽参、调用、回复。

**Q4：用户问「100 除以 0」，整条链路会发生什么？**
A：`chat` 命中计算意图 → `handleCalculate` 抽出 `op=divide, a=100, b=0` → 调 `calculate` 工具 → 工具按第五章逻辑返回 `CallToolResult.fail("除数不能为 0")`（`isError=true`，仍是成功的 JSON-RPC 响应）→ `toResponse` 识别 `isError`，返回 `success=false` + 原因文案。全程不抛协议级异常。

**Q5：V1 的关键词决策要升级成 LLM Function Calling，需要改动哪里？**
A：只改「决策」层——把 `availableTools`（工具名 + inputSchema）作为 function 定义传给 LLM，让模型输出「调哪个工具、参数是什么」，再交给现有的 `callTool` 执行。感知、行动、回复以及整个 MCP 协议层都不用动，体现「大脑与手脚解耦」。

---

## 八、本章练习答案

**练习1：给 Agent 新增一个「翻译」工具，需要做哪些事？Agent 主体要改吗？**
答：①在 Server 侧实现 `TranslateTool`（`name`/`definition`/`execute`，参考第五章）并注册；②Agent 重启后 `listTools` 自动发现它；③若仍用关键词决策，在 `chat` 加一条意图规则（如含「翻译」）+ 一个 `handleTranslate` 抽参调用。Agent 的**感知/行动/回复骨架不改**，只在决策层补一条规则；若用 LLM 决策则连这条都省了。

**练习2：如何证明 Agent「记录了完整调用链日志」？**
答：访问 `/api/mcp/chat?message=北京天气怎么样`，观察控制台会依次打印：`McpAgentService 收到用户输入` → `McpClient 调用工具 get_weather 完成, isError=false` → （`McpAgentService` 处理完成）。启动时还有 `Agent 启动，已发现工具: [...]`。这条从「感知」到「行动」的日志链就是调用链的体现（生产环境可按第七章加上 traceId 串联）。

**练习3：如果 `discoverTools` 启动时失败（Server 未就绪），`chat` 还能工作吗？**
答：`discoverTools` 失败只影响 `availableTools` 缓存（`/tools` 端点会返回 null），但 `chat` 里调用工具走的是 `mcpClient.callTool`，而 `McpClient` 有 `ensureInitialized` 自愈机制——首次调用会自动补做 `initialize`。所以 `chat` 仍可工作，只是「工具清单展示」暂时缺失。健壮做法是增加就绪探针/重试，确保启动感知成功。

---

## 结语：训练营回顾

八章走下来，我们从一条 JSON-RPC 报文开始，亲手搭出了传输层、客户端、工具服务端、工作流编排，最后总装成一个会「感知-决策-行动-回复」的 MCP Agent V1，并梳理了它走向生产的加固清单。你现在应该能回答这个核心问题：

> **MCP 到底解决了什么？** —— 它用一套标准协议，把「AI 的大脑（决策）」和「外部能力（工具）」彻底解耦，让工具可以独立开发、动态发现、随意增减，而 Agent 主体保持稳定。这就是「像插 USB 一样给 AI 插工具」的工程化答案。

至此，训练营四大目标（自动发现工具、自动调用工具、新增工具不改 Agent、完整调用链日志）全部达成。想深入可回看第六章（用 Workflow 编排多工具）与第七章（生产级加固清单）。