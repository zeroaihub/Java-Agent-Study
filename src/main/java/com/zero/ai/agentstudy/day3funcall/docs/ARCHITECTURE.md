# Day3 架构说明：Function Calling & Agent Assistant V1

> 本文档描述 Day3「Function Calling / Tool 调用」落地代码的整体架构、分层职责、请求流程与扩展方向。
> 所有代码位于独立包 `com.zero.ai.agentstudy.day3funcall`，基于 Spring Boot 3.4.5 + Spring AI 1.0 GA，可独立运行。

---

## 一、分层架构总览

```
HTTP 请求
   │
   ▼
Controller 层  (controller/Day3Controller)
   │  暴露 /day3/weather /agent /workflow /group /tools /assistant
   ▼
编排层
   ├─ Service   (service/Day3AgentService)   通用对话编排：挂载工具 → 自动 Agent Loop
   ├─ Assistant (assistant/AgentAssistantV1)  收官 Agent：system prompt + 三工具 + 异常兜底
   └─ Registry  (registry/ToolRegistry03)     工具目录：按场景分组取用工具
   ▼
Tool 层        (tool/WeatherTool03 / TimeTool03 / CalculatorTool03 / EmailTool03)
   │  @Tool + @ToolParam，返回结构化 JSON，参数前置校验
   ▼
Spring AI ChatClient ──► 大模型（OpenAI 兼容接口）
   │  自动 Function Calling：发 tools 菜单 → 解析 tool_calls → 执行工具 → 回传结果 → 综合作答
```

核心机制：你只需 `chatClient.prompt().tools(...).call()`，三阶段协议（发 tools / 解析 tool_calls / 回传结果 / 再请求）全部由 Spring AI 自动完成，形成 Agent Loop。

---

## 二、各层职责

| 层 | 类 | 职责 |
|----|----|----|
| Controller | `Day3Controller` | 暴露 6 个测试入口，参数默认值友好，直接返回自然语言 |
| Service | `Day3AgentService` | 三个方法：`chatWithWeather`（单工具）、`chat`（可变参多工具）、`chatWithSystem`（带人设多工具） |
| Assistant | `AgentAssistantV1` | 收官 Agent：固定 system prompt（人设「小智」+ 行为边界）、挂三工具、`ask()` 统一入口 + try-catch 兜底 |
| Registry | `ToolRegistry03` | 工具目录，按场景分组（assistant/office/all），`getToolsByGroup` / `listGroups` |
| Tool | `WeatherTool03` | 查天气，`@Tool` 描述 + 参数校验 + 返回 JSON（city/temp/desc/humidity） |
| Tool | `TimeTool03` | 查当前时间 |
| Tool | `CalculatorTool03` | 四则运算，op 枚举归一化 |
| Tool | `EmailTool03` | 发邮件（写操作），邮箱正则校验 + 返回 messageId |

---

## 三、六个入口与能力映射

| 入口 | 章节 | 挂载工具 | 演示能力 |
|------|------|----------|----------|
| `GET /day3/weather` | 四/五章 | WeatherTool | 单工具最小闭环 |
| `GET /day3/agent` | 五章 | Weather+Time+Calculator | 多工具语义路由，LLM 自动选择 |
| `GET /day3/workflow` | 六章 | Weather+Email | 多工具串行协同（Workflow 雏形） |
| `GET /day3/group` | 七章 | 按 group 动态取用 | 工具目录/分组挂载 |
| `GET /day3/tools` | 七章 | — | 查看工具目录（分组及工具数） |
| `GET /day3/assistant` | 八章 | Weather+Time+Calculator | 收官完整 Agent（人设+边界+兜底） |

---

## 四、请求流程（单工具 /day3/weather）

```
Client
  │ GET /day3/weather?msg=北京今天天气怎么样
  ▼
Day3Controller.weather
  ▼
Day3AgentService.chatWithWeather
  ├─ log 收到用户输入
  ├─ chatClient.prompt().user(msg).tools(weatherTool03).call()
  │     └─ Spring AI自动 Agent Loop:
  │          LLM 返回 tool_calls: getWeather("北京")
  │          → 执行 WeatherTool03.getWeather → 返回 JSON
  │          → 回传 LLM → LLM 组织自然语言
  └─ 返回自然语言回答
```

## 五、请求流程（多工具协同 /day3/workflow）

```
Client
  │ GET /day3/workflow?msg=查杭州天气，把结果发邮件到 boss@example.com
  ▼
Day3AgentService.chat(msg, weatherTool03, emailTool03)
  ▼
Spring AI 多轮 Agent Loop（串行依赖）：
  轮1: LLM 调 getWeather("杭州")           → 得到天气 JSON
  轮2: LLM 用天气结果组织正文 → 调 sendEmail(to, subject, content)
  轮3: LLM 综合两步结果 → "已查询杭州天气并发送邮件，messageId=..."
```

> 串行依赖的本质：后一个工具的入参来自前一个工具输出经 LLM 组织后的结果 —— 这就是「工具协同 / Workflow 雏形」。

## 六、请求流程（收官 Agent /day3/assistant）

```
Client
  │ GET /day3/assistant?msg=北京天气？现在几点？算 88 乘以 9
  ▼
AgentAssistantV1.ask
  ├─ try:
  │    chatClient.prompt()
  │       .system(SYSTEM_PROMPT)         人设「小智」+ 行为边界
  │       .user(msg)
  │       .tools(weather, time, calc)    三工具，LLM 自动选择/编排
  │       .call().content()
  └─ catch: 返回友好兜底话术（不把异常抛给用户）
```

---

## 七、Tool 设计原则（贯穿全部工具）

1. **描述即路由**：`@Tool(description=...)` 是 LLM 判断「何时调我」的唯一依据，需清晰列出触发场景。
2. **参数可读**：`@ToolParam(description=...)` 帮助 LLM 正确传参。
3. **返回结构化 JSON**：工具只返回数据，自然语言组织交给 LLM。
4. **参数前置校验**：非法参数返回 `{"code":"INVALID_PARAM",...}` 而非抛异常，避免炸穿 Agent Loop。
5. **写操作幂等**：`EmailTool03` 这类有副作用的工具，真实场景须基于业务唯一键做幂等去重。

---

## 八、扩展方向（V1 → 生产级 V2）

- **多轮记忆**：接入 ChatMemory，支持上下文对话（复用 Day2 `ConversationStore` 思路）。
- **工具治理**：`AgentAssistantV1` 硬编码挂三工具 → 接 `ToolRegistry03` 按场景/权限动态挂载。
- **权限校验**：工具执行前做硬校验，敏感操作二次确认。
- **可观测**：traceId 全链路 + Micrometer 指标（工具调用次数/耗时/成功率）+ AOP 日志切面。
- **Loop 上限**：显式设 `maxIterations` 防死循环。
- **超时/降级/熔断**：统一超时 + Resilience4j 熔断 + 降级话术。
- **安全**：prompt 注入防护。
- **流式输出**：SSE 流式（复用 Day2 stream 思路），边输出边体验。