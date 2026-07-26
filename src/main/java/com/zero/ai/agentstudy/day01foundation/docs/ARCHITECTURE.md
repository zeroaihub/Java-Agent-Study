# Day01 模块架构说明

本文档描述 Day01 第一个 AI Demo 的分层架构、调用链路与关键设计决策。

---

## 一、分层架构

采用经典的 Spring 分层结构，职责单一、依赖单向（上层依赖下层）：

```
Client (curl / 前端)
      │  HTTP POST /api/day01/chat
      ▼
┌─────────────────────────────┐
│  Controller 层               │  ChatController
│  - 参数校验 @Valid           │  - 请求出入口
│  - 统一包装 Result           │
└─────────────┬───────────────┘
              ▼
┌─────────────────────────────┐
│  Service 层                  │  ChatService
│  - 五大功能核心逻辑          │  - 组装 ChatClient 调用链
│  - 日志 / 耗时 / 异常        │
└─────────────┬───────────────┘
              ▼
┌────────────────────────────┐
│  Config / 基础设施层         │  ChatClientConfig
│  - ChatClient Bean           │  - defaultSystem 默认人设
└─────────────┬───────────────┘
              ▼
      Spring AI ChatClient
              │
              ▼
      LLM 服务（OpenAI / DeepSeek）
```

横切关注点：
- `common/Result` —— 统一返回结构
- `common/GlobalExceptionHandler` —— 全局异常兜底（限定本模块 controller 包）
- `dto/*` —— 请求/响应数据传输对象

---

## 二、调用时序

```
Client → ChatController.chat(@Valid ChatRequest)
       → ChatService.chat(request)
           1. 记录入参日志、计时开始
           2. chatClient.prompt()
           3. 若有 systemPrompt → .system(...)
           4. 若有 temperature  → .options(OpenAiChatOptions)
           5. .user(message).call().content()
           6. 记录耗时/出参长度，封装 ChatResponse
       → Result.success(ChatResponse)
       → JSON 响应
```

异常路径：Service 抛出 `RuntimeException` → `GlobalExceptionHandler` 捕获 → 返回 `Result.error(500, ...)`。
参数校验失败：`@Valid` 触发 `MethodArgumentNotValidException` → 返回 `Result.error(400, ...)`。

---

## 三、关键设计决策

| 决策 | 说明 | 理由 |
|------|------|------|
| ChatClient 作为 Bean 统一构建 | 在 `ChatClientConfig` 中集中配置 `defaultSystem` | 避免每次调用重复设置，便于统一治理 |
| System Prompt / Temperature 可运行时覆盖 | 请求级参数优先于默认值 | 兼顾统一人设与灵活定制 |
| 异常处理器限定 basePackages | 仅拦截 `day01foundation.controller` | 避免影响未来其他 dayXX 模块的异常处理 |
| 日志只记回答长度不记全文 | `answerLength` 而非完整回答 | 防止日志膨胀、规避敏感内容落盘 |
| 统一 Result 包装 | 所有接口返回 `Result<T>` | 前端处理一致、便于埋点与错误码治理 |

---

## 四、与主项目的关系

- 入口 `AgentStudyApplication`（`@SpringBootApplication`）可扫描到 `day01foundation` 包。
- 复用主项目 `src/main/resources/application.yml`（Spring AI 配置）。
- 本模块自成一体，不与其他 dayXX 模块产生耦合。

---

## 五、技术栈

| 组件 | 版本 / 说明 |
|------|-------------|
| JDK | 21 |
| Spring Boot | 4.1.0 |
| Spring AI | 2.0.0（BOM 管理） |
| 模型 Starter | spring-ai-starter-model-openai |
| 其他 | validation、lombok、webflux、fastjson2 |

---

## 六、扩展点

- 接入流式响应（`stream()` + SSE）——见 static/stream.html。
- 引入 Memory（对话上下文）为后续 Agent 打基础。
- 增加模型网关抽象，支持多模型路由与降级。