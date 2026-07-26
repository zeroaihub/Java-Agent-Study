# 第五章：Weather MCP Server —— 三个工具的实战与 inputSchema 精讲

> 五段式教学模板：为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化。
> 本章把前四章的“骨架”填上“血肉”：真正写三个能跑的工具 —— 天气、时间、计算器。
> 学完请回章末问题：“工具的业务失败为什么不抛异常，而要用 `CallToolResult.fail`？”

---

## 第一部分：为什么学（核心价值）

### 1. 前四章都是“框架”，工具才是“干活的人”

回顾一下：第三章的 [`McpServer`](../../mcp/server/McpServer.java:1) 只管协议分发，第四章的 [`McpClient`](../../mcp/client/McpClient.java:1) 只管打电话。它们都**不产生任何业务价值**——真正让 Agent “会查天气、会算数、会报时间”的，是实现了 [`McpTool`](../../mcp/tool/McpTool.java:1) 接口的一个个**工具类**。

这就像一家公司：Server 是总机，Client 是外线电话，而工具才是**真正接单干活的员工**。本章我们招三名员工：
- [`WeatherTool`](../../mcp/tool/WeatherTool.java:1)：查天气（**单个必填参数**示范）；
- [`TimeTool`](../../mcp/tool/TimeTool.java:1)：报时间（**无必填参数**示范）；
- [`CalculatorTool`](../../mcp/tool/CalculatorTool.java:1)：算四则（**多参数 + 枚举约束 + 除零业务失败**示范）。

### 2. 为什么工具要“自我描述”（definition）？

模型（大脑）并不认识你的 Java 方法，它只能读**文字描述**。所以每个工具都要回答三个问题，这三问就是 [`McpTool`](../../mcp/tool/McpTool.java:23) 接口的三个方法：

| 问题 | 方法 | 作用 |
| --- | --- | --- |
| 我叫什么？ | `name()` | 模型调用时的唯一标识（如 `get_weather`） |
| 我能干嘛、参数长啥样？ | `definition()` | 给模型读的“说明书”（含 inputSchema） |
| 怎么执行？ | `execute(args)` | 真正的业务逻辑 |

**核心洞察：`definition()` 是给模型看的“简历”，`execute()` 是员工真正的“手艺”。** 简历写得越清楚，模型越不会派错活。

### 3. 为什么这是“开闭原则（OCP）”最直接的落地？

第三章讲过 [`ToolRegistry`](../../mcp/registry/ToolRegistry.java:1) 用构造器注入 `List<McpTool>` 自动收集所有工具。这意味着：**新增一个工具 = 新增一个 `@Component` 类，Server / Registry / Client 一行都不用改。** 本章三个工具全都只关心自己的三件事，完全不知道 Server、Registry、Transport 的存在——这就是解耦的极致。

---

## 第二部分：是什么（核心概念）

### 2.1 一个工具的完整生命周期

```text
启动阶段：
  Spring 扫描到 @Component 的 WeatherTool
     → 放进 ToolRegistry 的 LinkedHashMap
     → tools/list 时，调用 definition() 收集进目录

运行阶段：
  模型读到 get_weather 的 definition（简历）
     → 决定调用，拼出 {"name":"get_weather","arguments":{"city":"北京"}}
     → McpServer 定位到 WeatherTool
     → 调用 execute({"city":"北京"})
     → 返回 CallToolResult
```

### 2.2 inputSchema：工具的“参数说明书”

`inputSchema` 是 [JSON Schema](https://json-schema.org/) 格式，模型据此知道“要传哪些参数、什么类型、哪些必填”。看 [`ToolDefinition.of`](../../entity/ToolDefinition.java:59) 如何把它拼出来：

```java
public static ToolDefinition of(String name,
                                String description,
                                Map<String, Object> properties,
                                String[] required) {
    Map<String, Object> schema = Map.of(
            "type", "object",           // 入参永远是一个对象
            "properties", properties,   // 每个参数的定义
            "required", required        // 哪些参数必填
    );
    return ToolDefinition.builder()
            .name(name)
            .description(description)
            .inputSchema(schema)
            .build();
}
```

**三个字段的分工：**
- `type: object`：MCP 约定入参永远是一个 JSON 对象；
- `properties`：一个 map，key 是参数名，value 是该参数的 schema（类型、描述、枚举等）；
- `required`：字符串数组，列出**必填**参数名，空数组 `{}` 表示“不传参数也能调”。

> 注意：字段名必须是 `inputSchema`（驼峰），这是 MCP 规范硬性规定。看 [`ToolDefinition`](../../entity/ToolDefinition.java:44) 用 `@JsonProperty("inputSchema")` 固定住，防止 Jackson 自动转成 `input_schema`。

### 2.3 CallToolResult：两种“错误”的分水岭

工具执行完，返回的不是裸字符串，而是 [`CallToolResult`](../../entity/CallToolResult.java:1)。它只有两个字段：

```java
private List<Map<String, Object>> content;  // 内容块数组，如 [{type:text, text:"..."}]
private boolean isError;                     // 是否为「工具业务失败」
```

两个静态工厂方法就是全部用法：

```java
CallToolResult.ok("北京今天天气：晴，26℃");   // isError=false，业务成功
CallToolResult.fail("除数不能为 0");          // isError=true，业务失败
```

**这是本章最重要的设计思想** —— 区分“两种错误”：

| 错误类型 | 举例 | 走哪条路 |
| --- | --- | --- |
| **协议级错误** | 方法不存在、参数结构非法 | `JsonRpcResponse.error`（第三章讲过，中断） |
| **工具级业务失败** | 城市不存在、除数为 0、时区非法 | `CallToolResult.fail`（success 响应 + isError=true） |

为什么业务失败不抛异常、不走协议错误？因为**要把“怎么办”的决定权交给模型**。城市查不到，模型可以换个城市重试；除数为 0，模型可以提示用户改输入。如果直接抛异常中断整条链路，模型就“瞎了”，只能干等着。

---

## 第三部分：怎么用（源码逐段精讲）

### 3.1 WeatherTool —— 单个必填参数的范式

先看 [`WeatherTool`](../../mcp/tool/WeatherTool.java:1) 的三段结构。

**① name()：工具的“工号”**

```java
public static final String TOOL_NAME = "get_weather";

@Override
public String name() {
    return TOOL_NAME;
}
```
用常量而非硬编码字符串，避免 `definition()` 和 `name()` 写不一致。

**② definition()：写“简历”，声明一个必填参数 city**

```java
@Override
public ToolDefinition definition() {
    Map<String, Object> cityProp = Map.of(
            "type", "string",
            "description", "要查询天气的城市名，如：北京、上海"
    );
    Map<String, Object> properties = Map.of("city", cityProp);
    return ToolDefinition.of(
            TOOL_NAME,
            "查询指定城市的实时天气。当用户询问某地天气、气温、是否下雨时使用。",
            properties,
            new String[]{"city"}   // ← city 是必填
    );
}
```
关键在 `new String[]{"city"}`：告诉模型“调我必须带 city 参数”。而 description 里那句“当用户询问某地天气……时使用”，是**给模型的触发提示**——写得越具体，模型越不会乱调。

**③ execute()：真正干活，三步走**

```java
@Override
public CallToolResult execute(Map<String, Object> arguments) {
    // 1) 参数校验：业务失败用 fail 返回，绝不抛异常
    if (arguments == null || arguments.get("city") == null) {
        return CallToolResult.fail("缺少必填参数 city");
    }
    String city = String.valueOf(arguments.get("city")).trim();
    if (city.isEmpty()) {
        return CallToolResult.fail("参数 city 不能为空");
    }

    // 2) 查询数据（此处用内存 Map 模拟，真实项目改成调气象 API 即可）
    String weather = MOCK_WEATHER.get(city);
    if (weather == null) {
        return CallToolResult.fail("暂无【" + city + "】的天气数据，请换一个城市试试");
    }

    // 3) 返回成功结果
    return CallToolResult.ok(city + "今天天气：" + weather);
}
```

注意三处 `fail` 的措辞：**都是“人话”**，因为这些文字最终会回给模型甚至用户看。“请换一个城市试试”比“NOT_FOUND”友好得多，也更利于模型自我纠正。

其中数据源是一个静态内存 Map：
```java
private static final Map<String, String> MOCK_WEATHER = new HashMap<>();
static {
    MOCK_WEATHER.put("北京", "晴，26℃，东北风3级");
    MOCK_WEATHER.put("上海", "多云，28℃，东南风2级");
    // ……广州、深圳、杭州
}
```
> **可替换性**：无论数据来自内存 Map 还是外部 HTTP API，`execute` 对外的接口不变。这正是“工具内部实现可自由替换”的好处。

### 3.2 TimeTool —— 无必填参数 + 可选参数校验

[`TimeTool`](../../mcp/tool/TimeTool.java:1) 演示的是“参数可传可不传”。看它的 `required` 是**空数组**：

```java
Map<String, Object> tzProp = Map.of(
        "type", "string",
        "description", "IANA 时区标识，如 Asia/Shanghai；不传则用服务器默认时区"
);
Map<String, Object> properties = Map.of("timezone", tzProp);
return ToolDefinition.of(
        TOOL_NAME,
        "获取当前日期和时间。当用户询问现在几点、今天日期时使用；可指定时区。",
        properties,
        new String[]{}   // ← 空数组：无必填参数
);
```
`new String[]{}` 告诉模型：“timezone 是可选的，你不传我也能跑。”

execute 里对可选参数的处理很典型——**给了就校验，没给就兜底**：

```java
Object tzArg = arguments == null ? null : arguments.get("timezone");
if (tzArg != null && !String.valueOf(tzArg).trim().isEmpty()) {
    String tz = String.valueOf(tzArg).trim();
    try {
        zoneId = ZoneId.of(tz);
    } catch (Exception e) {
        // 时区非法 → 业务失败，让模型/用户修正
        return CallToolResult.fail("无法识别的时区：" + tz + "，请使用如 Asia/Shanghai 的 IANA 时区标识");
    }
} else {
    zoneId = ZoneId.systemDefault();   // 缺省兜底
}
```
即使是可选参数，**给了错的值也要拦**（非法时区 → `fail`），这体现了“防御性编程”。

### 3.3 CalculatorTool —— 多参数 + 枚举约束 + 除零

[`CalculatorTool`](../../mcp/tool/CalculatorTool.java:1) 是三者中最复杂的，它有三个必填参数，且 `op` 有**枚举约束**：

```java
Map<String, Object> opProp = Map.of(
        "type", "string",
        "description", "运算类型",
        "enum", new String[]{"add", "subtract", "multiply", "divide"}  // ← 枚举
);
Map<String, Object> aProp = Map.of("type", "number", "description", "第一个操作数");
Map<String, Object> bProp = Map.of("type", "number", "description", "第二个操作数");

Map<String, Object> properties = Map.of("op", opProp, "a", aProp, "b", bProp);
return ToolDefinition.of(
        TOOL_NAME,
        "对两个数字进行四则运算（加/减/乘/除）。当用户需要精确计算时使用。",
        properties,
        new String[]{"op", "a", "b"}   // ← 三个都必填
);
```
`enum` 字段的威力：模型读到后就知道“op 只能是这四个值之一”，从而不会传 `"plus"` 这种它自己编的运算符。

execute 的除零处理是“业务失败”的经典范例：

```java
switch (op) {
    case "add" -> result = a + b;
    case "subtract" -> result = a - b;
    case "multiply" -> result = a * b;
    case "divide" -> {
        if (b == 0) {
            return CallToolResult.fail("除数不能为 0");   // ← 除零 = 业务失败，不是崩溃
        }
        result = a / b;
    }
    default -> {
        return CallToolResult.fail("不支持的运算类型：" + op + "，仅支持 add/subtract/multiply/divide");
    }
}
```
除零如果写成 `a / b` 让它抛 `ArithmeticException`，那就会被第三章的 Server 兜底成 `-32000` 协议错误，链路中断。用 `fail` 返回，模型能看到“除数不能为 0”，从而提示用户。**同样的失败，两种处理，用户体验天差地别。**

---

## 第四部分：用在哪（真实场景）

- **天气/时间/计算** 这三类正是 Agent Demo 里最常见的“开胃菜”工具，因为它们分别覆盖了三种参数形态（必填/可选/枚举）。
- 真实企业里，把 `WeatherTool` 的内存 Map 换成调用**内部微服务**或**第三方 API**，其余代码（definition、Server、Client、Agent）完全不动——这就是 MCP 的价值：**业务工具与协议解耦**。
- 一旦你掌握“写一个 McpTool”的套路，就能把公司里任何一个能力（查订单、发短信、查库存）包装成模型可自动调用的工具。

---

## 第五部分：避坑优化

| 坑 | 现象 | 正确做法 |
| --- | --- | --- |
| 业务失败抛异常 | 城市查不到直接 throw，链路中断 | 用 `CallToolResult.fail`，交模型判断 |
| description 太笼统 | 模型不知道何时该调、乱调 | 写清“当用户……时使用”的触发条件 |
| required 漏写 | 模型不传必填参，execute 空指针 | `required` 数组务必与实际校验一致 |
| name 与 definition 不一致 | tools/call 找不到工具 | 用一个 `TOOL_NAME` 常量统一 |
| 可选参数不校验 | 传了非法值直接崩 | 可选参数“给了就校验，没给就兜底” |
| inputSchema 字段名写错 | 模型读不到参数说明 | 固定用 `inputSchema`（`@JsonProperty` 保证） |

---

## 核心知识速记

- 一个工具 = `name()` + `definition()` + `execute()` 三件事；
- `definition()` 是给模型看的“简历”，`inputSchema` 是参数说明书（type/properties/required）；
- `required` 空数组 = 无必填参数；`enum` = 参数值枚举约束；
- **两种错误**：协议级错误走 `JsonRpcResponse.error`（中断）；业务失败走 `CallToolResult.fail`（success + isError=true，交模型判断）；
- 新增工具 = 新增一个 `@Component`，Server/Registry/Client 零改动（OCP）；
- fail 的文案要写“人话”，因为它最终会回给模型/用户。

---

## 常见面试题

**Q1：工具的业务失败为什么不抛异常、不走协议错误？**
A：因为要把“怎么办”的决定权交给模型。业务失败（城市不存在、除零）用 `CallToolResult.fail`（isError=true）返回，模型能看到失败原因，从而重试或换策略；若抛异常中断链路，模型就失去了自愈能力。协议错误（方法不存在）才走 `JsonRpcResponse.error`。

**Q2：inputSchema 里的 required 空数组代表什么？**
A：代表该工具没有必填参数，模型不传任何参数也能调用。TimeTool 就是例子——timezone 可选，缺省用服务器时区。

**Q3：新增一个“查汇率”工具，需要改哪些已有类？**
A：一个都不用改。只需新增一个 `implements McpTool` 且标注 `@Component` 的类，Spring 会自动把它注入 ToolRegistry 的 `List<McpTool>`，tools/list 自动收录，Server/Client/Agent 全都不动。这是开闭原则（OCP）的直接体现。

**Q4：如何让模型知道一个参数只能取固定几个值？**
A：在该参数的 schema 里加 `enum` 字段，如 CalculatorTool 的 `op` 用 `"enum", new String[]{"add","subtract","multiply","divide"}`，模型读到后就不会传枚举外的值。

---

## 本章练习答案

**练习：工具的业务失败为什么不抛异常，而要用 `CallToolResult.fail`？**

答：MCP 刻意把“工具业务失败”设计成正常响应里的一个 `isError=true` 标志位，而非协议错误或异常，原因有三：

1. **保留模型的决策权**：失败信息（如“除数不能为 0”“暂无该城市数据”）随 success 响应返回给模型，模型能据此重试、换参数或提示用户，而不是被一个中断的链路“闷死”。
2. **区分职责**：协议级错误（方法不存在、参数结构非法）是“系统坏了”，该中断；业务失败是“逻辑上没成功”，属正常业务分支，不该让整条 JSON-RPC 链路崩掉。
3. **可观测性更好**：`fail` 的文案是人话，既能回给模型，也能直接展示给用户；而异常堆栈对模型和用户都是噪音。

对照代码：WeatherTool 城市查不到、TimeTool 时区非法、CalculatorTool 除数为 0，三者全部用 `CallToolResult.fail`，无一抛异常——这就是把“是否重试”的选择权交还给上层的统一范式。

---

> 下一章预告：第六章将把工具串起来——**Workflow + MCP**，看多个工具如何在一条工作流里被编排调用，实现“查天气→根据天气算建议”这类组合能力。