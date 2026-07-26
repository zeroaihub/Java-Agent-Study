# 第六章 Workflow + MCP：把「单次工具调用」升级为「多步编排」

> 本章配套可运行代码目录：`day07mcp/workflow/`
> 前置章节：第四章（`McpClient`）、第五章（Weather MCP Server 三工具）

---

## 一、为什么要学（Why）

前面五章我们已经打通了「模型 ↔ 工具」的**单次调用**能力：给定一个工具名和参数，`McpClient.callTool` 就能返回结果。但真实业务几乎从不是「一步到位」的：

- 「查完天气」之后，往往还要「据此给出行/穿衣建议」；
- 「查完订单」之后，往往还要「计算优惠 → 生成话术 → 发通知」；
- 「读文件」之后，往往还要「抽取关键字段 → 写入数据库」。

这些场景的共同点是：**多个能力需要按顺序串起来，前一步的输出是后一步的输入**。如果全塞进一个大方法里，很快就会变成一团意大利面——分支越来越多、职责越来越乱、无法复用、无法观测。

**Workflow（工作流编排）** 就是解决这个问题的标准范式：把每个步骤抽象成一个「节点（Node）」，用一个「引擎」按顺序驱动它们，节点之间通过一块共享「黑板」传递数据。这样：

- 每个节点只做一件事，易读易测；
- 想加/减/换步骤，只是增删节点，引擎代码一行不改；
- 天然产生一条可追踪的「调用链」，便于观测与排错。

本章我们不引入任何重量级工作流框架，而是**手写一个最小可用的顺序编排引擎**，让你看清「编排」的本质，同时把它**架在第四章的 `McpClient` 之上**，做到「工作流层完全不侵入 MCP 协议层」。

---

## 二、是什么（What）

本章新增的 `workflow` 包共 8 个类，各司其职：

| 类 | 角色 | 一句话职责 |
| --- | --- | --- |
| `WorkflowContext` | 黑板 | 节点间传递数据的共享容器 |
| `WorkflowNode` | 节点接口 | 统一所有步骤的抽象（异构节点归一） |
| `Workflow` | 引擎 | 顺序执行节点、失败即停、记录调用链 |
| `WorkflowResult` | 结果体 | 统一返回：成没成 + 执行了哪些节点 + 产出 |
| `McpToolNode` | 通用节点 | 把「调一个 MCP 工具」封装成标准节点 |
| `WeatherAdviceNode` | 本地节点 | 纯本地规则加工，演示节点间数据依赖 |
| `WorkflowService` | 装配车间 | 把节点组装成有业务意义的工作流 |
| `WorkflowController` | HTTP 入口 | 对外暴露一个触发工作流的端点 |

### 2.1 三个核心设计模式

**① 黑板模式（Blackboard）——`WorkflowContext`**

节点之间不互相持有引用、不互相调用，而是共享一块「黑板」：上游把结果写进黑板的某个 `key`，下游从同一个 `key` 读出来。节点只认识 `key`，谁也不认识谁，从而**彻底解耦**。

**② 接口抽象统一异构节点——`WorkflowNode`**

不管一个步骤是「调远程 MCP 工具」还是「跑一段本地规则」，它们都实现同一个接口。引擎 `Workflow` 只依赖这个接口（依赖倒置 DIP），因此对「调工具的节点」和「本地计算的节点」一视同仁——这就是「异构节点归一」。

**③ 组合优于继承——`McpToolNode` 的 `argBuilder`**

我们不给每个工具都写一个子类（`WeatherNode`、`CalcNode`……那样会类爆炸），而是写**一个通用的 `McpToolNode`**，把「怎么从上下文拼出这个工具的入参」这件事，用一个 `Function<WorkflowContext, Map>` 参数化传进去。查天气和算数复用同一个类，只是构造参数不同。

---

## 三、怎么用（How）——源码逐段精讲

### 3.1 黑板：`WorkflowContext`

```java
public class WorkflowContext {
    private final Map<String, Object> input = new LinkedHashMap<>();  // 初始输入
    private final Map<String, Object> data  = new LinkedHashMap<>();  // 节点产出

    public WorkflowContext withInput(String key, Object value) { input.put(key, value); return this; }
    public Object getInput(String key) { return input.get(key); }
    public void put(String key, Object value) { data.put(key, value); }
    public Object get(String key) { return data.get(key); }
    public String getString(String key) { Object v = data.get(key); return v == null ? "" : String.valueOf(v); }
    public Map<String, Object> snapshot() { return new LinkedHashMap<>(data); }
}
```

要点：
- **input 与 data 分开**：`input` 存用户最初给的（如 `city`），`data` 存节点跑出来的中间/最终产出。分开是为了语义清晰——用户输入是只读的事实，节点产出是流程状态。
- **`getString` 是 null 安全的**：读不到就返回空串，下游节点无需到处判空。
- **`snapshot` 返回副本**：结果体 `output` 用它，避免把内部可变 map 直接暴露出去。
- 用 `LinkedHashMap` 是为了**保持写入顺序**，打印调用链时更直观。

### 3.2 节点接口：`WorkflowNode`

```java
public interface WorkflowNode {
    String name();                          // 节点名，用于日志/调用链
    boolean execute(WorkflowContext context); // true=成功可继续；false=业务失败应中断
}
```

要点：
- **`execute` 用返回值而非异常表达「业务失败」**。业务失败（如缺数据）返回 `false`，由引擎决定是否中断；只有真正的**意外异常**才抛出。这呼应了第五章「两种错误分层」的思想——可预期的失败走正常返回值，不可预期的才走异常通道。
- 接口只有两个方法，**极简**。节点内部想干什么引擎完全不管，这正是 OCP（对扩展开放）的基础。

### 3.3 引擎：`Workflow`

```java
public WorkflowResult run(WorkflowContext context) {
    List<String> executed = new ArrayList<>();
    for (WorkflowNode node : nodes) {
        boolean ok;
        try {
            ok = node.execute(context);
        } catch (Exception e) {                       // 意外异常兜底
            return WorkflowResult.fail(name, executed, "节点[" + node.name() + "]执行异常: " + e.getMessage(), context.snapshot());
        }
        executed.add(node.name());
        if (!ok) {                                    // 业务失败：中断后续
            String reason = context.getString("error");
            return WorkflowResult.fail(name, executed, reason.isEmpty() ? "节点[" + node.name() + "]失败" : reason, context.snapshot());
        }
    }
    return WorkflowResult.ok(name, executed, context.snapshot());
}
```

要点：
- **顺序执行 + 失败即停**：这是最基础也是最常用的编排语义。
- **两层保护**：`try/catch` 兜住意外异常，`if (!ok)` 处理可预期的业务失败，两者都会中断并把「已执行到哪一步」`executed` 返回，便于定位。
- **引擎完全不认识任何具体节点**，只循环调 `node.execute`。所以想编排新流程，只需 `addNode` 不同组合，引擎代码零改动（OCP + DIP）。

### 3.4 结果体：`WorkflowResult`

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class WorkflowResult {
    private String workflow;            // 工作流名
    private boolean success;            // 整体成没成
    private List<String> executedNodes; // 实际执行到的节点（能看出在哪步停下）
    private String error;               // 失败原因（成功时 null）
    private Map<String, Object> output; // 最终产出快照

    public static WorkflowResult ok(...)   { ... success(true) ... }
    public static WorkflowResult fail(...) { ... success(false).error(error) ... }
}
```

要点：`executedNodes` 是**可观测性**的关键——出错时一眼看出流程停在了哪一步；`output` 是黑板快照，让调用方拿到所有中间与最终产出。`ok`/`fail` 静态工厂让引擎侧代码更干净。

### 3.5 通用 MCP 工具节点：`McpToolNode`（本章胶水核心）

```java
public class McpToolNode implements WorkflowNode {
    private final String nodeName, toolName, outputKey;
    private final McpClient mcpClient;
    private final Function<WorkflowContext, Map<String, Object>> argBuilder; // 参数化拼参

    @Override public String name() { return nodeName; }

    @Override public boolean execute(WorkflowContext context) {
        Map<String, Object> args = argBuilder.apply(context);   // 1) 从上下文拼入参
        if (args == null) args = new LinkedHashMap<>();
        CallToolResult result = mcpClient.callTool(toolName, args); // 2) 复用第四章 McpClient
        String text = result.asText();
        context.put(outputKey, text);                            // 3) 产出写回黑板
        if (result.isError()) {                                  // 4) 业务失败→写 error 返回 false
            context.put("error", "工具[" + toolName + "]失败: " + text);
            return false;
        }
        return true;
    }
}
```

要点：
- **它只依赖 `McpClient`**（第四章成果），完全不碰 JSON-RPC、Transport 等协议细节——工作流层「构建于 MCP 之上」，两层彻底解耦。
- **`argBuilder` 是灵魂**：同一个 `McpToolNode` 类，传不同的 `argBuilder` 就能调不同的工具。查天气传 `ctx -> Map.of("city", ...)`，算数就传 `ctx -> Map.of("op","add","a",1,"b",2)`。这是「组合优于继承」的教科书示范。
- **完整对接第五章的错误分层**：`callTool` 返回的 `CallToolResult` 若 `isError()`（业务失败，如城市不存在），本节点写 `error` 并返回 `false`，交由引擎中断——业务失败不抛异常。

### 3.6 本地加工节点：`WeatherAdviceNode`（演示节点间数据依赖）

```java
public class WeatherAdviceNode implements WorkflowNode {
    private final String weatherKey, adviceKey;
    @Override public String name() { return "weather_advice"; }

    @Override public boolean execute(WorkflowContext context) {
        String weather = context.getString(weatherKey);   // 读上游 McpToolNode 写入的天气
        if (weather.isEmpty()) { context.put("error", "缺少上游天气数据，无法生成建议"); return false; }
        StringBuilder advice = new StringBuilder("出行建议：");
        if (weather.contains("雨")) advice.append("有降雨，记得带伞；");
        if (weather.contains("雷")) advice.append("有雷电，尽量减少户外活动；");
        if (weather.contains("晴")) advice.append("天气晴好，注意防晒；");
        if (weather.contains("阴") || weather.contains("多云")) advice.append("云量较多，适合出行；");
        advice.append("请根据气温增减衣物。");
        context.put(adviceKey, advice.toString());          // 产出写回黑板
        return true;
    }
}
```

要点：
- 这是个**纯本地节点**，不调任何 MCP 工具，用来演示两件事：**①节点间数据依赖**——它读的 `weatherKey` 正是上一个 `McpToolNode` 写进去的，「上一步的输出=下一步的输入」；**②工作流的异构性**——本地节点和工具节点混在同一条流水线里，引擎一视同仁。
- 真实项目里，这段「关键词规则」可以换成「调 LLM 生成建议」，但结构不变——这正是节点抽象的价值。

### 3.7 装配车间：`WorkflowService`

```java
@Service
public class WorkflowService {
    private static final String KEY_WEATHER = "weatherText";
    private static final String KEY_ADVICE  = "advice";
    private final McpClient mcpClient;
    public WorkflowService(McpClient mcpClient) { this.mcpClient = mcpClient; }

    @PostConstruct public void init() { mcpClient.initialize(); } // 启动即握手

    public WorkflowResult runWeatherAdvice(String city) {
        WorkflowContext context = new WorkflowContext().withInput("city", city);
        Workflow workflow = new Workflow("weather-advice")
            .addNode(new McpToolNode("query_weather", "get_weather", mcpClient,
                     ctx -> Map.of("city", String.valueOf(ctx.getInput("city"))), KEY_WEATHER))
            .addNode(new WeatherAdviceNode(KEY_WEATHER, KEY_ADVICE));
        return workflow.run(context);
    }
}
```

要点：
- `WorkflowService` 是**唯一知道「业务流程长什么样」的地方**——它用声明式的 `addNode` 链把「查天气 → 出建议」串起来。引擎、节点都不知道整条流程，只有它知道。
- **两个节点用同一个 `KEY_WEATHER` 约定传值**：`McpToolNode` 写入，`WeatherAdviceNode` 读出。这就是黑板协作的落地。
- **复用而非改动**：`@PostConstruct` 里调 `mcpClient.initialize()` 完成握手；`McpClient.initialize` 是幂等的（内部 `volatile initialized` 标志位），所以它和第八章的 `McpAgentService` 同时调用也不冲突。

### 3.8 HTTP 入口：`WorkflowController`

```java
@RestController
@RequestMapping("/api/mcp/workflow")
public class WorkflowController {
    private final WorkflowService workflowService;
    public WorkflowController(WorkflowService workflowService) { this.workflowService = workflowService; }

    @GetMapping("/weather-advice")
    public WorkflowResult weatherAdvice(@RequestParam(defaultValue = "北京") String city) {
        return workflowService.runWeatherAdvice(city);
    }
}
```

启动应用后，浏览器访问 `GET /api/mcp/workflow/weather-advice?city=北京`，即可看到完整的执行链路与最终建议。

---

## 四、用在哪（Where）

| 场景 | 工作流编排的价值 |
| --- | --- |
| 客服机器人 | 查订单 → 查物流 → 计算赔付 → 生成话术，逐步串联 |
| 数据处理 | 拉取 → 清洗 → 抽取 → 入库，每步一个节点 |
| Agent 多工具协作 | 一次用户请求需要连续调用多个 MCP 工具时的编排骨架 |
| 审批流 | 每个审批节点一个 Node，失败即停并记录停在哪一步 |

**一句话**：只要业务是「多步、有先后、前一步产出喂给后一步」，就适合用 Workflow 编排；本章的引擎就是这类需求的最小骨架。

---

## 五、避坑与优化

| 坑 | 现象 | 正确做法 |
| --- | --- | --- |
| 节点间直接持有引用 | 节点耦合、无法复用 | 一律通过 `WorkflowContext` 黑板传值 |
| 业务失败也抛异常 | 引擎难以区分「可预期失败」与「意外崩溃」 | 业务失败返回 `false`，意外才抛异常 |
| 给每个工具写一个节点子类 | 类爆炸 | 用通用 `McpToolNode` + `argBuilder` 参数化 |
| 忘记 MCP 握手 | 首个工具节点调用报未初始化 | `@PostConstruct` 或依赖 `McpClient` 的自愈 `ensureInitialized` |
| 黑板 key 写死在多处 | key 拼错、难维护 | 用常量（如 `KEY_WEATHER`）集中管理 |

**进阶优化方向（本章未实现，供思考）**：并行节点（fork/join）、条件分支（if 节点）、循环节点、失败重试与补偿、节点级超时、把执行链路持久化用于审计。

---

## 六、核心知识速记

- Workflow 解决「单次调用不够」的问题：**多步、有序、前后依赖**。
- **黑板模式**（`WorkflowContext`）让节点解耦：只认识 key，不认识彼此。
- **`WorkflowNode` 接口**统一异构节点：调工具的、本地算的，引擎一视同仁。
- **引擎 `Workflow`** 只依赖接口（DIP），新增节点零改引擎（OCP）。
- **`McpToolNode` + `argBuilder`** 用组合复用：一个类适配所有 MCP 工具。
- 工作流层**复用 `McpClient`、不改 MCP 协议层**，两层解耦。
- 业务失败走返回值 `false`，意外崩溃走异常——延续第五章「两种错误分层」。

---

## 七、常见面试题

**Q1：Workflow 相比「单次工具调用」的价值是什么？**
A：单次调用只能完成一个原子能力；Workflow 能把多个能力按「前一步输出=后一步输入」的方式编排成更高层的复合能力，且每步职责单一、可复用、有可追踪的调用链，便于观测与排错。

**Q2：为什么新增一个节点不需要改引擎？**
A：因为引擎 `Workflow` 只依赖 `WorkflowNode` 接口（依赖倒置 DIP），运行时只循环调 `node.execute`，不认识任何具体节点。新增节点只是实现接口并 `addNode`，符合开闭原则（对扩展开放、对修改关闭）。

**Q3：节点之间如何传递数据？为什么不让节点互相调用？**
A：通过共享的 `WorkflowContext`（黑板）：上游 `put(key, value)`，下游 `get(key)`。节点只认识 key，不持有彼此引用，从而解耦——想调整顺序或替换节点都不影响其他节点。

**Q4：一个 `McpToolNode` 如何适配不同工具？**
A：把「怎么拼这个工具的入参」用 `Function<WorkflowContext, Map>` 参数化传入（`argBuilder`），再传入 `toolName` 和 `outputKey`。这样查天气、算数复用同一个类，只是构造参数不同——组合优于继承，避免类爆炸。

**Q5：工作流里的业务失败和意外异常怎么区分处理？**
A：可预期的业务失败（如缺数据、工具 `isError`）让节点 `execute` 返回 `false`，引擎中断并把原因保留在 `error` 里；不可预期的异常直接抛出，被引擎 `try/catch` 兜底同样中断。两者都会返回「已执行到哪一步」，便于定位。

---

## 八、本章练习答案

**练习1：为 `calculate` 工具编排一条「先加后判断」的工作流。**
答：复用 `McpToolNode`，`toolName="calculate"`，`argBuilder = ctx -> Map.of("op","add","a",ctx.getInput("a"),"b",ctx.getInput("b"))`，产出写入 `sumText`；再加一个本地节点读 `sumText` 做阈值判断。无需新写工具节点子类，体现 `argBuilder` 的复用力。

**练习2：如果 `get_weather` 查了一个不存在的城市，工作流会怎样？**
答：`get_weather` 返回 `CallToolResult.isError()=true`，`McpToolNode` 写入 `error` 并返回 `false`，引擎中断，`WorkflowResult.success=false`、`executedNodes=[query_weather]`（停在第一步）、`error` 为工具失败原因。`WeatherAdviceNode` 不会执行。

**练习3：如何让引擎支持「某节点失败不中断、继续往下走」？**
答：在 `WorkflowNode` 或引擎里引入「可选/容错节点」概念——例如给节点加 `boolean optional()`，引擎遇到 `optional` 节点失败时只记录不中断。这属于对引擎的扩展，但因为节点抽象稳定，改动集中在引擎的循环逻辑里，不影响已有节点。

---

> 下一章预告：**第七章 企业最佳实践**——把前面的裸实现打磨成生产级：超时、重试、连接复用、异常治理、日志与可观测性、配置化管理。