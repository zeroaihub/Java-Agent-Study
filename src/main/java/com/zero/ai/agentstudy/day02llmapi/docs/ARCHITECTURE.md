# Day02 架构说明：AI Chat Service V1

> 本文档描述 Day02「大模型 API 调用与 AI 应用工程基础」落地代码的整体架构、分层职责、请求流程与扩展方向。
> 所有代码位于独立包 `com.zero.ai.agentstudy.day02llmapi`，与 Day01 完全隔离，可独立运行。

---

## 一、分层架构总览

```
HTTP 请求
   │
   ▼
Controller 层  (controller/Day02ChatController)
   │  参数接收 + @Valid 校验 + 返回体包装 R<T>
   ▼
Service 层     (service/Day02ChatService + impl/Day02ChatServiceImpl)
   │  业务编排：限流→ traceId 日志 → 调模型 → 采集 Token → 多轮历史
   ▼
Config 层      (config/Day02ChatClientConfig + Day02ChatProperties)
   │  独立 Bean day02ChatClient + 外置配置 day02.chat.*
   ▼
Session 层     (session/ConversationStore)
   │  多轮会话内存存储 + 窗口裁剪
   ▼
Spring AI ChatClient ──► 大模型（OpenAI 兼容接口，本地 127.0.0.1:1234）
```

横切能力：
- `exception/Day02ExceptionHandler`：限定包异常处理（basePackages 限定 Day02 controller）
- `common/R`：统一返回体
- `dto/ChatRequest` `dto/ChatResponse`：出入参契约

---

## 二、各层职责

| 层 | 类 | 职责 |
|----|----|----|
| Controller | `Day02ChatController` | 暴露 `/api/day02/chat`、`/chat/stream`、`/chat/multi` 三接口，参数校验，包装 `R<T>` |
| Service 接口 | `Day02ChatService` | 定义 chat / chatStream / chatMulti 三能力契约 |
| Service 实现 | `Day02ChatServiceImpl` | 限流、结构化日志+traceId、脱敏、调用 ChatClient、Token 采集、多轮拼接 |
| Config | `Day02ChatClientConfig` | 构建独立 `@Bean("day02ChatClient")`，规避与 Day01 `chatClient` 冲突 |
| Config | `Day02ChatProperties` | `@ConfigurationProperties(prefix="day02.chat")` 外置配置，全带默认值 |
| Session | `ConversationStore` | conversationId → 历史消息，append/getHistory/clear/trim |
| Exception | `Day02ExceptionHandler` | 400/429/502/500 分级处理，限定包不污染全局 |
| DTO | `ChatRequest`/`ChatResponse` | 入参校验（JSR-303）/ 出参含 Token、耗时、降级标记 |
| Common | `R<T>` | code/message/data 统一返回体 |

---

## 三、请求流程（非流式 /chat）

```
Client
  │ POST /api/day02/chat  { message, systemPrompt?, temperature? }
  ▼
Day02ChatController.chat  →  @Valid 校验入参
  ▼
Day02ChatServiceImpl.chat
  ├─ acquirePermit()          限流（SimpleRateLimiter 令牌桶）
  ├─ newTraceId()             生成 8 位 traceId
  ├─ log.info(msgLen...)      脱敏日志（只记长度）
  ├─ chatClient.prompt()...call().chatResponse()
  ├─ build()                  采集 usage(promptTokens/completionTokens/totalTokens) + costMs
  └─ return ChatResponse
  ▼
R.ok(chatResponse)  →  Client
```

## 四、请求流程（流式 /chat/stream，SSE）

```
Client (EventSource)
  │ POST /api/day02/chat/stream  produces=text/event-stream
  ▼
Day02ChatServiceImpl.chatStream
  └─ chatClient.prompt()...stream().content()  →  Flux<String>
         ├─ doOnComplete  记录完成
         ├─ doOnCancel    记录客户端断连
         └─ onErrorResume 降级为一段兜底文案
  ▼
逐 chunk 下发（打字机效果）
```

> 注意：`application.yml` 中 `web-application-type: servlet`，Spring MVC 6+ 支持直接返回 `Flux` 做 SSE；`spring.mvc.async.request-timeout=600000` 保证长流不超时。

## 五、请求流程（多轮 /chat/multi）

```
Client
  │ POST /api/day02/chat/multi  { message, conversationId }
  ▼
Day02ChatServiceImpl.chatMulti
  ├─ 校验 conversationId 非空
  ├─ ConversationStore.getHistory(id)      取历史 List<Message>
  ├─ chatClient.prompt().messages(history).user(msg).call()
  ├─ build()
  └─ ConversationStore.append(id, msg, answer)  回写历史 + trim 窗口裁剪
```

---

## 六、异常流程

| 异常 | HTTP | 返回 code | 场景 |
|------|------|-----------|------|
| `MethodArgumentNotValidException` | 400 | 400 | @Valid 校验失败 |
| `IllegalArgumentException` | 400 | 400 | 多轮缺 conversationId |
| `IllegalStateException` | 429 | 429 | 触发限流 |
| `RuntimeException` | 502 | 502 | 上游模型调用失败（降级） |
| `Exception` | 500 | 500 | 兜底未知异常 |

---

## 七、与 Day01 的隔离策略

1. **包隔离**：独立 `day02llmapi` 包，不改任何 Day01 文件。
2. **Bean 名隔离**：`@Bean("day02ChatClient")` + `@Qualifier` 精确注入，规避 Day01 全局 `chatClient` 冲突。
3. **异常隔离**：`@RestControllerAdvice(basePackages="...day02llmapi.controller")` 限定拦截范围。
4. **返回体隔离**：Day02 用自有 `R<T>`，不复用 Day01`Result`。
5. **配置隔离**：`day02.chat.*` 独立前缀，且全带默认值，无需改 yml 也能启动。

---

## 八、扩展方向

- **会话存储**：`ConversationStore` 内存版 → Redis/DB，加 TTL 与淘汰，解决重启丢失、无法水平扩展、内存泄漏三大问题。
- **限流**：`SimpleRateLimiter` → Redis + Lua 分布式限流 / Sentinel。
- **重试降级**：接入 `@Retryable`/`@Recover` 或 Resilience4j 熔断。
- **可观测性**：接入 Micrometer + Prometheus（Token/耗时/QPS 指标）、链路追踪（traceId 打通全链路）。
- **多模型路由**：抽象 Provider，按成本/能力路由不同模型。