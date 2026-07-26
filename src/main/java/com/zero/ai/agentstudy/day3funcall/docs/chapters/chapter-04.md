# 第四章：Spring AI Tool Calling

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day3 · 第四章
>
> 教学框架（五段式）：① 为什么学 → ② 是什么 → ③ 怎么用 → ④ 用在哪 → ⑤ 避坑与优化

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

**一句话价值**：Spring AI 让 Java 工程师用最熟悉的"注解 + Bean"方式做 Agent，零协议负担。

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
│  ⑥ LLM生成: "北京今天晴，26℃，不用带伞"          │
└──────────────────────────────────────────────────┘
   │
   ▼
返回给用户："北京今天晴，26℃，湿度适中，不用带伞。"
```

---

## ③ 怎么用（实战演练）

### 第 1 步：定义工具（WeatherTool.java）

核心是 `@Tool` + `@ToolParam` + 返回 JSON：

```java
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

```java
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

```java
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

```yaml
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

```python
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

```java
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
- **Q：ChatClient 和 ChatModel 区？**
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

## 本章练习

启动应用（需配好可用的 OpenAI 兼容模型），访问：
`http://localhost:8080/day3/weather?msg=杭州今天要带伞吗？`

观察控制台是否打印 `[WeatherTool] 被调用`，并思考：**如果我再加一个 TimeTool，LLM 怎么知道该调 WeatherTool 还是 TimeTool？**

（这正是第五章的内容：实现三个 Tool，看 LLM 如何在多个工具间自动选择。）

---

> 下一章 → [第五章：Java 实现三个 Tool](chapter-05.md)（Calculator/Time/Weather，看 LLM 如何在多工具间自动选择）