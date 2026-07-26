# 第五章：Java 实现三个 Tool

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day3 · 第五章
>
> 教学框架（五段式）：① 为什么学 → ② 是什么 → ③ 怎么用 → ④ 用在哪 → ⑤ 避坑与优化

> 本章代码已在项目中实现并编译通过：
> - `day3funcall/tool/WeatherTool.java`（第四章已建）
> - `day3funcall/tool/CalculatorTool.java`
> - `day3funcall/tool/TimeTool.java`
> - 多工具入口：`day3funcall/controller/Day3Controller.java` 的 `GET /day3/agent`

---

## ① 为什么学（核心价值）

第四章只有一个工具，LLM“没得选”。真实 Agent 都有多个工具，核心问题变成：

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

**关键**：LLM 选工具靠的是 **description 的语义匹配**。描述写得越清晰、越贴合用户说法，选得越准。这就是为什么第三章强调“description 是给 LLM 的说明书”。

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

这就是**多工具协同的雏形**，第六章会深入到“工具间有依赖”的 Workflow。

---

## ③ 怎么用（实战演练）

### CalculatorTool（计算器）

要点：LLM 算数不可靠 → 交给 Tool；op 用枚举 + 归一化兼容多种写法；除零兜底。

```java
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

要点：LLM 不知道“现在” → 交给 Tool；支持时区，非法时区兜底为系统默认。

```java
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

```java
public String chat(String userMessage, Object... tools) {
    return chatClient.prompt().user(userMessage).tools(tools).call().content();
}
```

Controller 一次挂载三个：

```java
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
- **TimeTool 类**：任何“相对时间”处理（“明天”、“下周三”）的第一步都是拿到“现在”，再由 LLM/工具推算。
- **WeatherTool 类**（外部 API 模式）：查快递、查股价、查库存、查天气……所有“实时外部数据”都套这个模式：`@Tool → 调外部 API → 返回结构化 JSON`。

### 真实案例：AI 办公助手

用户：“帮我算下这个季度 3 个月的总销售额，分别是 120 万、95 万、138 万，再看看今天几号方便我写周报”

```
LLM 一次编排两个工具：
  calculate(120+95+138) → 353 万
  getCurrentTime()      → 2026-07-03
汇总："本季度总销售额 353 万元；今天是 2026 年 7 月 3 日。"
```

---

## ⑤ 避坑与优化（进阶提升）

### 常见错误

1. **多个工具 description 语义重叠** → LLM 纠结选哪个。要让每个工具的“触发场景”互斥、清晰。
2. **参数类型用包装类可能为 null** → 基本类型 double 更安全（本例 Calculator 用 double）。
3. **枚举参数不做归一化** → LLM 传“乘”/“×”/“multiply” 都可能出现，必须 normalize。
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
2. 思考并尝试：给 CalculatorTool 再加一个“求平方根”能力，应该新增一个工具方法，还是在 calculate 里加分支？（提示：单一职责——新增独立方法更好。）

---

> 下一章 → [第六章：多个 Tool 协同工作](chapter-06.md)（Weather + Email 串行协同，上一个工具的输出作为下一个工具的输入，这就是 Workflow 的基础）