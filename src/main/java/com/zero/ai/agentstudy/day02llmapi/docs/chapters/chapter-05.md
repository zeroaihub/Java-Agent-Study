# 第五章：用 Spring AI 实现 Chat 服务

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day02 · 第五章
>
> 五段式教学模板：**为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化**。
> 本章把前四章的原理**全部落地成完整可运行的 Java 代码**：分层架构 + 非流式 + 流式 + 多轮会话。
> 学完请完成章末任务：**「独立跑通 /api/day02/chat 三个接口，并说明每层的职责」**。

---

## 第一部分：为什么学（核心价值）

### 1. 为什么要「分层」而不是全写在 Controller 里？

初学者最爱把所有逻辑塞进一个 Controller 方法：接参、拼 Prompt、调模型、处理异常、返回——**一个方法两百行**。这在 demo 阶段能跑，但在企业里是灾难：

- 改一个 Prompt 要动 Controller，容易误伤接口。
- 换一个模型厂商要满世界改代码。
- 想加日志、限流、重试，无处下手。
- 没法单元测试，一测就要起整个 Web 容器。

**分层架构的本质：让每一层只干一件事，改动被隔离在最小范围内。** 这是从「会调 API」到「会做系统」的分水岭。

### 2. 为什么本章代码要用独立包和独立 Bean？

本项目 Day1（`day01foundation`）已经定义了一个全局的 `chatClient` Bean 和自己的异常处理器。如果 Day2 直接复用同名 Bean、同一套异常处理，就会**互相污染、Bean 冲突、启动报错**。

所以本章的**核心工程约束**是：

- **独立包名**：`com.zero.ai.agentstudy.day02llmapi`。
- **独立 Bean 名**：用 `@Bean("day02ChatClient")`，注入时用 `@Qualifier` 精确指定。
- **限定异常处理器**：`@RestControllerAdvice(basePackages = "...day02llmapi.controller")`，只管本模块的接口。
- **独立接口前缀**：`/api/day02/**`。

> 这样 Day2 和 Day1 可以在**同一个 Spring Boot 应用里共存、各自独立运行、互不影响**。

---

## 第二部分：是什么（分层架构拆解）

本章要建的分层结构如下（全部在 `day02llmapi` 包内）：

```
day02llmapi/
├── config/
│   └── Day02ChatClientConfig.java   # 构建独立的 day02ChatClient Bean
├── dto/
│   ├── ChatRequest.java             # 请求 DTO（含参数校验）
│   └── ChatResponse.java            # 响应 DTO（含内容、Token 用量）
├── service/
│   ├── Day02ChatService.java        # 业务接口
│   └── impl/Day02ChatServiceImpl.java # 业务实现（非流式/流式/多轮）
├── controller/
│   └── Day02ChatController.java     # HTTP 入口，暴露三个接口
├── exception/
│   └── Day02ExceptionHandler.java   # 仅拦截本模块异常
└── common/
    └── R.java                       # 本模块统一返回体（不复用 Day1 的 Result）
```

各层职责一句话讲清：

| 层 | 职责 | 一句话 |
|----|------|--------|
| **Controller** | 接收 HTTP 请求、参数校验、调用 Service、包装返回 | 只做「翻译」，不写业务 |
| **Service** | 编排业务逻辑、拼装 Prompt、调用模型客户端 | 大脑，真正干活 |
| **Config** | 构建 / 装配 ChatClient 等 Bean | 只管「怎么造对象」 |
| **DTO** | 定义请求 / 响应的数据结构 + 校验规则 | 接口的「合同」 |
| **Exception** | 统一异常兜底，返回友好错误 | 不让异常裸奔到前端 |
| **Common** | 通用返回体、工具 | 全模块复用的基础件 |

---

## 第三部分：怎么用（完整代码落地）

> 以下代码将由我在本章配套**真实写入项目**，此处先讲清每个文件的设计意图。所有代码基于本项目已有依赖（Spring Boot 3.4.5 / Java 17 / Spring AI 1.0.0），**独立可运行，不改 Day1**。

### 1. 统一返回体 `R.java`

为了不与 Day1 的 `Result` 耦合，Day2 用自己的 `R<T>`（结构一致：`code / message / data`），提供 `ok()` / `fail()` 静态方法。

### 2. 请求 DTO `ChatRequest.java`

```java
@Data
public class ChatRequest {
    @NotBlank(message = "message 不能为空")
    private String message;              // 用户提问

    private String systemPrompt;         // 可选：自定义人设

    @Min(0) @Max(2)
    private Double temperature;          // 可选：随机性 0~2

    private String conversationId;       // 可选：多轮会话 ID
}
```

- `@NotBlank / @Min / @Max` → 结合 Controller 的 `@Valid` 做**参数校验**，非法请求在入口就被拦下。
- `conversationId` → 多轮会话的关键：同一个 ID 的对话共享历史记忆。

### 3. 响应 DTO `ChatResponse.java`

封装 `content`（回答文本）、`promptTokens / completionTokens / totalTokens`（Token 用量，回应第二章的 `usage`）、`conversationId`。

### 4. 独立 ChatClient 配置 `Day02ChatClientConfig.java`

```java
@Configuration
public class Day02ChatClientConfig {

    /** Day02 专用 ChatClient，Bean 名独立，避免与 Day1 的 chatClient 冲突 */
    @Bean("day02ChatClient")
    public ChatClient day02ChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是 Day02 AI Chat Service，一位专业严谨的 AI 助手。")
                // 装配多轮记忆顾问（Advisor），支持按 conversationId 记忆上下文
                .defaultAdvisors(new MessageChatMemoryAdvisor(new InMemoryChatMemory()))
                .build();
    }
}
```

- `@Bean("day02ChatClient")` → **独立 Bean 名**，是避免冲突的关键。
- `MessageChatMemoryAdvisor + InMemoryChatMemory` → Spring AI 提供的**多轮记忆机制**，第三章讲的 `assistant` 历史消息就靠它自动维护。

### 5. 业务实现 `Day02ChatServiceImpl.java`（核心）

三个方法对应三大能力：

```java
@Service
@RequiredArgsConstructor
public class Day02ChatServiceImpl implements Day02ChatService {

    @Qualifier("day02ChatClient")
    private final ChatClient chatClient;

    // 能力①：非流式对话
    @Override
    public ChatResponse chat(ChatRequest req) {
        var callResponse = chatClient.prompt()
                .system(sp -> { if (req.getSystemPrompt() != null) sp.text(req.getSystemPrompt()); })
                .user(req.getMessage())
                .options(OpenAiChatOptions.builder()
                        .temperature(req.getTemperature() == null ? 0.7 : req.getTemperature())
                        .build())
                .call()
                .chatResponse();  // 拿到完整 ChatResponse，含 usage
        // 抽取内容与 Token 用量，组装成 DTO 返回（省略组装细节，见落地代码）
        return build(callResponse);
    }

    // 能力②：流式对话
    @Override
    public Flux<String>chatStream(ChatRequest req) {
        return chatClient.prompt()
          .user(req.getMessage())
                .stream()
                .content();       // 返回 Flux<String>，每段一个 delta
    }

    // 能力③：多轮会话（靠 conversationId 记忆）
    @Override
    public ChatResponse chatMulti(ChatRequest req) {
        return build(chatClient.prompt()
                .user(req.getMessage())
                .advisors(a -> a.param(
                        AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                        req.getConversationId()))
                .call()
                .chatResponse());
    }
}
```

- `@Qualifier("day02ChatClient")` → 精确注入 Day2 的 Bean，**不会误用 Day1 的**。
- `.call().chatResponse()` → 拿完整响应（含 `usage`），比 `.content()` 信息更全，用于统计 Token。
- 多轮：通过 advisor 传入 `conversationId`，同一个 ID 自动带上历史消息。

### 6. HTTP 入口 `Day02ChatController.java`

```java
@RestController
@RequestMapping("/api/day02")
@RequiredArgsConstructor
public class Day02ChatController {

    private final Day02ChatService chatService;

    // 非流式
    @PostMapping("/chat")
    public R<ChatResponse> chat(@Valid @RequestBody ChatRequest req) {
        return R.ok(chatService.chat(req));
    }

    // 流式（SSE）
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatRequest req) {
        return chatService.chatStream(req);
    }

    // 多轮会话
    @PostMapping("/chat/multi")
    public R<ChatResponse> multi(@Valid @RequestBody ChatRequest req) {
        return R.ok(chatService.chatMulti(req));
    }
}
```

- 三个接口路径：`/api/day02/chat`、`/api/day02/chat/stream`、`/api/day02/chat/multi`，**与 Day1 完全隔离**。
- `@Valid` → 触发 DTO 上的校验注解。
- 流式接口 `produces = TEXT_EVENT_STREAM_VALUE` → 走 SSE（第四章原理）。

### 7. 模块异常处理器 `Day02ExceptionHandler.java`

```java
@RestControllerAdvice(basePackages = "com.zero.ai.agentstudy.day02llmapi.controller")
public class Day02ExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldError().getDefaultMessage();
        return R.fail(400, "参数错误：" + msg);
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleAll(Exception e) {
        return R.fail(500, "服务异常：" + e.getMessage());
    }
}
```

- `basePackages` 限定**只拦截 Day2 的 Controller**，不影响 Day1 的异常处理器。这是多模块共存的关键技巧。

---

## 第四部分：用在哪（10 个真实场景）

1. **企业内部 AI 助手**：这套分层就是最小可上线骨架，直接改 Prompt 即可交付。
2. **多产品线共用一个后端**：像 Day1/Day2 这样按模块隔离，一个应用承载多条业务线。
3. **模型可插拔**：换厂商只改 Config 层的 base-url / model，业务层零改动。
4. **灰度与 A/B**：Service 层根据参数路由到不同模型 / 不同 Prompt，做效果对比。
5. **多轮客服机器人**：`conversationId` 天然支持会话记忆，用户多轮追问不丢上下文。
6. **流式写作工具**：`/chat/stream` 直接对接前端打字机效果。
7. **参数可控的开放平台**：把 `temperature` 等透传给调用方，让下游自定义风格。
8. **统一鉴权 / 限流接入**：所有请求走 Controller 入口，方便加拦截器、AOP。
9. **可观测**：Service 层集中埋点，统计每次调用的 Token、耗时、成功率。
10. **单元测试友好**：Service 与 Controller 解耦，可 Mock ChatClient 单测业务逻辑。

---

## 第五部分：避坑优化（10 条企业级红线）

1. **Bean 冲突**：绝不能再定义一个叫 `chatClient` 的 Bean（Day1 已有），必须用 `@Bean("day02ChatClient")` + `@Qualifier` 精确注入。
2. **异常处理器全局污染**：`@RestControllerAdvice` 不加 `basePackages` 会拦截全项目（含 Day1）接口，务必限定包。
3. **DTO 校验别漏 `@Valid`**：DTO 上写了 `@NotBlank` 但 Controller 忘加 `@Valid`，校验形同虚设。
4. **流式接口用错返回类型**：流式必须返回 `Flux` 且声明 SSE，写成 `String` 就退化成阻塞返回。
5. **多轮会话内存泄漏**：`InMemoryChatMemory` 只适合 demo，生产会话越堆越多撑爆内存，要接 Redis / 数据库并设过期。
6. **`conversationId` 缺失导致串会话**：多轮接口若不校验 `conversationId` 非空，可能把不同用户的对话记忆混在一起，造成信息泄漏。
7. **Token 用量不采集**：`.call().content()` 只拿文本丢了 `usage`，应改用 `.chatResponse()` 采集 Token 做成本核算。
8. **硬编码 Prompt**：System Prompt 写死在代码里，改一次要重新发版，应外置到配置 / 数据库（第三章「Prompt 工程化」）。
9. **未做超时与降级**：模型服务挂了不能让请求无限挂起，要配超时 + fallback（第六章详解）。
10. **日志泄漏敏感信息**：直接打印完整请求 / 响应可能把用户隐私、API Key 记进日志，要脱敏。

---

## 本章面试高频题（7 道）

1. **为什么要分层？各层职责是什么？** 参考：隔离变化、便于测试与维护；Controller 接参转发、Service 编排业务、Config 造 Bean、DTO 定合同、Exception 兜底。
2. **一个 Spring Boot 应用里两个模块都要用 ChatClient，怎么避免 Bean 冲突？** 参考：用带名字的 `@Bean("xxx")` + `@Qualifier` 精确注入；或用 `@Primary` 指定默认。
3. **`@RestControllerAdvice` 如何只作用于某个模块？** 参考：指定 `basePackages` 限定扫描范围。
4. **`.call().content()` 和 `.call().chatResponse()` 区别？** 参考：前者只拿文本，后者拿完整响应（含 `usage`、`finish_reason` 等元信息）。
5. **Spring AI 多轮会话怎么实现？** 参考：`MessageChatMemoryAdvisor` + `ChatMemory`（如 `InMemoryChatMemory`），按 `conversationId` 维护历史消息。
6. **DTO 参数校验怎么做？** 参考：DTO 加 JSR-303 注解（`@NotBlank` 等）+ Controller 加 `@Valid`，配合异常处理器返回友好提示。
7. **生产环境的会话记忆为什么不能用 `InMemoryChatMemory`？** 参考：内存不可持久化、无法水平扩展、会无限增长导致 OOM，应用 Redis / DB 并设过期与淘汰。

---

## 章末任务 ✅

> **请在本地独立跑通 Day02 的三个接口**：
> 1. `POST /api/day02/chat`（非流式）
> 2. `POST /api/day02/chat/stream`（流式）
> 3. `POST /api/day02/chat/multi`（多轮，用同一个 `conversationId` 连续问两次，验证 AI 记得上文）
>
> 并用自己的话说明：**为什么这套代码能和 Day1 共存而不冲突？**

### 我的答案（练习区）

```text
（在这里记录你的运行结果与思考……）
```

---

> 下一章预告：**第六章 · 企业级优化**——日志、异常、配置外置、超时重试、限流降级、可观测性，把这套「能跑」的代码升级成「能扛生产流量」的服务。