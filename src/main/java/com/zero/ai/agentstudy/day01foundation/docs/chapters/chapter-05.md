# 第五章 完成你的第一个 AI Demo

> 五段式教学：**为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化**
>
> 环境搭好了，接下来是激动人心的时刻——写出你的第一个真正能对话的 AI 程序。本章我们将实现一个包含 **普通聊天、System Prompt 设定、模型参数控制、异常处理、日志记录** 五大功能的完整 Demo，所有代码可直接运行，全部落在 `day01foundation` 目录，不影响你未来的项目。

---

## 第一部分：为什么学（Why）——为什么第一个 Demo 要"麻雀虽小五脏俱全"？

很多教程的"第一个 AI Demo"只有一行：`chatClient.prompt("你好").call()`。跑通了，然后呢？你依然不知道**企业里真正的 AI 接口长什么样**。

作为资深 Java 工程师，你写接口从来不会只写一行——你会考虑分层、参数校验、异常处理、日志。**AI 接口同样如此**。一个能上生产的 AI 接口，至少要回答五个问题：

| 问题 | 对应功能 |
|------|----------|
| 用户怎么和 AI 对话？ | 普通聊天接口 |
| 怎么让 AI 扮演特定角色？ | System Prompt |
| 怎么控制 AI 的"性格"（严谨/发散）？ | 模型参数（Temperature 等） |
| 调用失败了怎么办？ | 异常处理 |
| 怎么排查线上问题、算成本？ | 日志记录 |

本章的 Demo 就是围绕这五个问题设计的。**我们不做"玩具"，而是做一个能直接演化成生产接口的骨架**。学完这一章，你就掌握了所有 AI 应用最核心的调用模式。

---

## 第二部分：是什么（What）——Demo 的整体架构

我们遵循第四章的分层规范，Demo 的调用链路如下：

```
HTTP 请求
   │
   ▼
┌──────────────────┐
│  ChatController  │  接收请求、参数校验、返回统一结果
└────────┬─────────┘
         │ 调用
         ▼
┌──────────────────┐
│   ChatService    │  编排 AI 调用、拼装 Prompt、记录日志、异常兜底
└────────┬─────────┘
         │ 使用
         ▼
┌──────────────────┐
│   ChatClient     │  Spring AI 提供，真正对接大模型
└────────┬─────────┘
         │ HTTP
         ▼
   大模型服务（云端）
```

涉及的类：

| 类 | 职责 | 分层 |
|----|------|------|
| `ChatController` | HTTP 接口、参数校验 | controller |
| `ChatService` | AI 调用编排、日志、异常兜底 | service |
| `ChatClientConfig` | 装配 `ChatClient` Bean | config |
| `ChatRequest` | 请求 DTO | dto |
| `ChatResponse` | 响应 DTO | dto |
| `Result<T>` | 统一返回包装 | common |

### 核心概念：ChatClient

`ChatClient` 是 Spring AI 1.0 的核心面（Fluent API），用链式调用表达"和大模型对话"。基本用法：

```java
String answer = chatClient
        .prompt()                    // 开始构建一次对话
        .system("你是一位Java专家")   // 设定 System Prompt（角色）
        .user("什么是多态？")         // 用户问题
        .call()                      // 发起调用
        .content();                  // 取出文本回答
```

是不是很像你用过的各种 Fluent API（比如 `WebClient`、`StreamAPI`）？**Spring AI 刻意把它设计得符合 Java 工程师直觉**。

---

## 第三部分：怎么用（How）——完整代码实现（可直接运行）

下面给出全套代码。**请严格按包路径创建文件**，所有代码位于 `com.zero.ai.agentstudy.day01foundation` 下。

### 3.1 统一返回结果 Result<T>

先建一个统一返回包装，这是企业接口的标配。

**文件路径**：`day01foundation/common/Result.java`

```java
package com.zero.ai.agentstudy.day01foundation.common;

import lombok.Data;

/**
 * 统一 API 返回结果包装
 *
 * @param <T> 数据类型
 */
@Data
public class Result<T> {

    /** 业务状态码：200 成功，非 200 失败 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 成功（带数据） */
    public static <T> common.day01foundation.com.zero.ai.agentstudy.Result<T> success(T data) {
        common.day01foundation.com.zero.ai.agentstudy.Result<T> r = new common.day01foundation.com.zero.ai.agentstudy.Result<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /** 失败（带错误信息） */
    public static <T> common.day01foundation.com.zero.ai.agentstudy.Result<T> error(int code, String message) {
        common.day01foundation.com.zero.ai.agentstudy.Result<T> r = new common.day01foundation.com.zero.ai.agentstudy.Result<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(null);
        return r;
    }
}
```

### 3.2 请求 DTO：ChatRequest

**文件路径**：`day01foundation/dto/ChatRequest.java`

```java
package com.zero.ai.agentstudy.day01foundation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求参数
 */
@Data
public class ChatRequest {

    /** 用户输入的问题（必填） */
    @NotBlank(message = "message 不能为空")
    private String message;

    /**
     * System Prompt：设定 AI 的角色/行为。可选。
     * 例如："你是一位资深Java架构师，回答要专业、简洁。"
     */
    private String systemPrompt;

    /**
     * 温度：0.0 ~ 2.0，越大越发散，越小越严谨。可选。
     * 不传时使用配置文件默认值。
     */
    private Double temperature;
}
```

### 3.3 响应 DTO：ChatResponse

**文件路径**：`day01foundation/dto/ChatResponse.java`

```java
package com.zero.ai.agentstudy.day01foundation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** AI 生成的回答 */
    private String answer;

    /** 本次使用的模型名称 */
    private String model;

    /** 耗时（毫秒），便于观测性能 */
    private long costMs;
}
```

### 3.4 配置类：ChatClientConfig

Spring AI 的 Starter 已自动装配了 `ChatClient.Builder`，我们基于它构建一个带默认 System Prompt 的 `ChatClient` Bean。

**文件路径**：`day01foundation/config/ChatClientConfig.java`

```java
package com.zero.ai.agentstudy.day01foundation.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置类
 * <p>
 * Spring AI 的 openai starter 会自动注入 ChatClient.Builder，
 * 我们在此基础上设置一个全局默认的 System Prompt。
 */
@Configuration
public class ChatClientConfig {

    /**
     * 构建全局 ChatClient Bean
     *
     * @param builder Spring AI 自动装配的 Builder
     * @return 配置好的 ChatClient
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                // 全局默认角色：如果调用方没传 systemPrompt，就用这个
                .defaultSystem("你是一位专业、友好的AI助手，回答准确、简洁，避免废话。")
                .build();
    }
}
```

> **注意**：`ChatClient.Builder` 是 Spring AI Starter 自动提供的 Bean，直接注入即可，无需手动 new。这就是第四章讲的 Starter"开箱即用"。

### 3.5 业务层：ChatService（本 Demo 的核心）

这是最关键的一层，实现了五大功能：普通聊天、动态 System Prompt、动态温度、异常处理、日志记录。

**文件路径**：`day01foundation/service/ChatService.java`

```java
package com.zero.ai.agentstudy.day01foundation.service;

import dto.day01foundation.com.zero.ai.hub.agentstudy.ChatRequest;
import dto.day01foundation.com.zero.ai.hub.agentstudy.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 聊天业务服务
 * <p>
 * 封装五大功能：普通聊天、System Prompt、模型参数、异常处理、日志记录。
 */
@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;

    /** 从配置读取默认模型名，用于返回展示 */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String defaultModel;

    public ChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 执行一次聊天
     *
     * @param request 聊天请求
     * @return 聊天响应
     */
    public ChatResponse chat(ChatRequest request) {
        long start = System.currentTimeMillis();

        // 【功能5-日志】记录入参（生产环境注意脱敏与长度截断）
        log.info("[AI-Chat] 收到请求, message={}, systemPrompt={}, temperature={}",
                request.getMessage(), request.getSystemPrompt(), request.getTemperature());

        try {
            // 【功能1-普通聊天】构建 prompt
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt();

            // 【功能2-System Prompt】调用方传了就用它覆盖默认角色
            if (StringUtils.hasText(request.getSystemPrompt())) {
                spec = spec.system(request.getSystemPrompt());
            }

            // 【功能3-模型参数】调用方传了温度就动态设置
            if (request.getTemperature() != null) {
                spec = spec.options(OpenAiChatOptions.builder()
                        .temperature(request.getTemperature())
                        .build());
            }

            // 发起调用，取出文本回答
            String answer = spec
                    .user(request.getMessage())
                    .call()
                    .content();

            long cost = System.currentTimeMillis() - start;
            // 【功能5-日志】记录出参与耗时
            log.info("[AI-Chat] 调用成功, 耗时={}ms, answerLength={}", cost,
                    answer == null ? 0 : answer.length());

            return new ChatResponse(answer, defaultModel, cost);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            // 【功能4-异常处理】记录异常，向上抛出业务异常由全局处理器兜底
            log.error("[AI-Chat] 调用失败, 耗时={}ms, error={}", cost, e.getMessage(), e);
            throw new RuntimeException("AI 服务调用失败：" + e.getMessage(), e);
        }
    }
}
```

> **代码讲解**：
> - **功能1**：`chatClient.prompt().user(...).call().content()` 完成一次最基本的对话。
> - **功能2**：通过 `spec.system(...)` 动态覆盖 System Prompt，实现"角色可切换"。
> - **功能3**：通过 `OpenAiChatOptions` 动态设置 `temperature`，实现"性格可调"。
> - **功能4**：`try-catch` 捕获所有异常，记录后抛出统一业务异常，避免把底层堆栈直接暴露给前端。
> - **功能5**：入口、出口、异常处各记一条日志，含耗时——这是排查线上问题和统计成本的基础。

### 3.6 接口层：ChatController

**文件路径**：`day01foundation/controller/ChatController.java`

```java
package com.zero.ai.agentstudy.day01foundation.controller;

import common.day01foundation.com.zero.ai.hub.agentstudy.Result;
import dto.day01foundation.com.zero.ai.hub.agentstudy.ChatRequest;
import dto.day01foundation.com.zero.ai.hub.agentstudy.ChatResponse;
import service.day01foundation.com.zero.ai.hub.agentstudy.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 聊天接口
 */
@RestController
@RequestMapping("/api/day01/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 聊天接口
     *
     * @param request 请求体（@Valid 触发参数校验）
     * @return 统一结果
     */
    @PostMapping
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = chatService.chat(request);
        return Result.success(response);
    }
}
```

### 3.7 全局异常处理器：GlobalExceptionHandler

统一兜底异常，返回规范错误结构，避免堆栈泄露给前端。

**文件路径**：`day01foundation/common/GlobalExceptionHandler.java`

```java
package com.zero.ai.agentstudy.day01foundation.common;

import common.day01foundation.com.zero.ai.agentstudy.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数校验异常（@NotBlank 等触发） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("参数校验失败");
        return Result.error(400, msg);
    }

    /** 兜底：其他所有异常（含 AI 调用失败） */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[GlobalException] 未处理异常", e);
        return Result.error(500, e.getMessage());
    }
}
```

### 3.8 配置文件 application.yml

**文件路径**：`src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}            # 从环境变量读取，见第四章
      base-url: https://api.deepseek.com    # 示例用 DeepSeek，用 OpenAI 则改回官方地址
      chat:
        options:
          model: deepseek-chat              # 与 base-url 成对匹配
          temperature: 0.7                  # 默认温度

logging:
  level:
    com.zero.ai.agentstudy.day01foundation: info
```

### 3.9 运行与测试

**第一步**：确保环境变量已设置（见第四章）：

```bash
echo $OPENAI_API_KEY   # 应能打印出 Key
```

**第二步**：启动项目：

```bash
mvn spring-boot:run
```

看到 `Started ... in x seconds` 表示启动成功。

**第三步**：用 curl 测试三种场景。

场景 A —— 普通聊天：

```bash
curl -X POST http://localhost:8080/api/day01/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"用一句话解释什么是多态"}'
```

场景 B —— 自定义角色（System Prompt）：

```bash
curl -X POST http://localhost:8080/api/day01/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"介绍下你自己","systemPrompt":"你是一只傲娇的猫娘，说话带喵"}'
```

场景 C —— 调整温度 + 触发校验：

```bash
# 低温度（更严谨）
curl -X POST http://localhost:8080/api/day01/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"1+1等于几","temperature":0.1}'

# 空 message，触发参数校验，应返回 code=400
curl -X POST http://localhost:8080/api/day01/chat \
  -H "Content-Type: application/json" \
  -d '{"message":""}'
```

预期返回（场景 A）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "answer": "多态是指同一个方法调用，因对象实际类型不同而表现出不同行为。",
    "model": "deepseek-chat",
    "costMs": 860
  }
}
```

**到这里，你的第一个"五脏俱全"的 AI 接口就跑通了！**

---

## 第四部分：用在哪（Where）——这个 Demo 骨架能演化成什么？

别小看这个 Demo，它是几乎所有 AI 应用的"原型"。结合你的个人项目：

### 场景 1：你的「AI 公众号自动化」——文章生成接口

把 `systemPrompt` 固定为"你是一位资深科技自媒体作者，风格犀利有观点"，`message` 换成文章主题，就是一个**公众号选题/初稿生成接口**。异常处理和日志让你能监控每篇文章的生成耗时和失败率。

### 场景 2：你的「AI 工具导航站」——工具摘要接口

把 `systemPrompt` 设为"你是工具评测专家，用50字总结工具核心价值"，`message` 传工具介绍文本，就能**批量生成工具摘要**。`temperature` 调低（如 0.2）保证摘要稳定不发散。

### 场景 3：你的「AI 办公助手」——多角色助手

同一个接口，靠切换 `systemPrompt` 就能变身"会议纪要助手""邮件润色助手""周报生成助手"。这正是本 Demo"动态 System Prompt"设计的价值——**一套接口，多种人格**。

### 场景 4：你的「AI 量化交易 Agent」——舆情分析

`systemPrompt` 设为"你是金融舆情分析师，判断以下新闻对股价是利好/利空/中性，只输出结论"，`temperature` 设 0，`message` 传新闻文本。低温度保证判断稳定可复现——这对交易决策至关重要。

### 骨架的可扩展点

| 现在的功能 | 未来可扩展为 |
|-----------|-------------|
| 单轮聊天 | 多轮对话（加 Memory，后续章节） |
| 同步 call() | 流式输出 stream()（打字机效果） |
| 纯文本回答 | 结构化输出（返回 JSON 对象） |
| 无工具 | Function Calling（调用外部 API/数据库） |

**这些都是在本章骨架上"长"出来的**，所以打好这个基础极其重要。

---

## 第五部分：避坑优化（Optimization）——Demo 阶段的 5 个高频坑

### 坑 1：启动成功但调用报 401

见第四章坑 3。**先查 Key、base-url、model 三者是否匹配**，别怀疑代码。

### 坑 2：ChatClient.Builder 注入失败

**现象**：启动报 `No qualifying bean of type 'ChatClient.Builder'`。

**根因**：没引入 `spring-ai-starter-model-openai`，或配置里缺 `api-key`（Starter 条件装配不生效）。

**解决**：确认 pom.xml 有该 Starter；确认 `application.yml` 里 `spring.ai.openai.api-key` 有值。

### 坑 3：Temperature 设置不生效

**现象**：动态传了 temperature，但回答风格没变化。

**根因**：`options(...)` 会**整体覆盖**默认 options，若只 build 了 temperature 可能丢失其他默认配置；或模型对温度不敏感。

**解决**：本 Demo 写法（只设 temperature）多数场景够用；如需保留其他参数，应基于默认 options 复制后再改。

### 坑 4：日志打印完整回答导致刷屏 / 泄露

**现象**：把 AI 完整回答打进日志，长文本刷屏，甚至泄露敏感内容。

**根因**：日志未做长度截断和脱敏。

**解决**：如本 Demo，日志只记 `answerLength` 而非全文；生产环境对 message 也应截断/脱敏。

### 坑 5：没有超时控制，接口被拖死

**现象**：大模型响应慢，请求线程长时间挂起，高并发下线程池被打满。

**根因**：未配置调用超时。

**解决**：可通过 Spring AI 底层 `RestClient`/`WebClient` 配置超时；进阶做法是加线程池隔离 + 熔断（后续企业架构章节展开）。

### 优化建议

1. **参数校验前置**：能在 Controller 用 `@Valid` 拦截的，绝不进 Service 浪费一次大模型调用（省钱）。
2. **日志带 traceId**：企业环境接入链路追踪，每次调用带唯一 traceId，便于排查。
3. **耗时监控**：把 `costMs` 上报监控系统，AI 调用是慢操作，必须可观测。

---

## 核心知识速记

| 知识点 | 一句话记忆 |
|--------|-----------|
| ChatClient | Spring AI 核心 Fluent API：prompt→system→user→call→content |
| System Prompt | 设定 AI 角色，可全局默认 + 动态覆盖 |
| Temperature | 0 严谨可复现，高值发散有创意 |
| 分层 | Controller 校验、Service 编排、Config 装配 |
| 异常处理 | try-catch + 全局 @RestControllerAdvice 兜底 |
| 日志 | 记入参/出参/耗时，回答记长度不记全文 |
| 参数校验 | @Valid + @NotBlank，省一次大模型调用 |

---

## 思考题（请先自己思考，再看下方答案）

**思考题 1**：本 Demo 把 `systemPrompt` 设计成"可选参数、不传就用全局默认"，这种设计有什么好处？和纯前端写死 Prompt 相比呢？

**思考题 2**：为什么金融舆情分析、数学计算这类场景要把 `temperature` 设为接近 0，而写文案要设高一些？

**思考题 3**：本 Demo 的 `ChatService` 用 try-catch 捕获异常后又抛出 `RuntimeException`，为什么不直接把原始异常抛给前端？

---

## 常见面试题（企业视角）

**Q1：Spring AI 的 ChatClient 和直接用 HttpClient 调 OpenAI API 有什么区别？**
A：ChatClient 提供统一 Fluent 抽象，自动处理请求构建、序列化、模型切换、重试等，业务代码与具体模型解耦；直接用 HttpClient 需手写请求体、解析响应、处理各家差异，耦合且易错。

**Q2：System Prompt 和 User Prompt 有什么区别？**
A：System Prompt 定义 AI 的角色、行为准则、约束（"你是谁、该怎么做"）；User Prompt 是用户的具体问题（"我要问什么"）。System 优先级更高、更稳定，用于框定回答风格与边界。

**Q3：Temperature 参数的作用？生产环境怎么选？**
A：控制生成随机性。0 附近确定性高、可复现，适合分类/抽取/计算/代码；0.7~1.0 更有创意，适合文案/创作。生产按场景选，需稳定结果就调低。

**Q4：AI 接口的异常和日志设计要注意什么？**
A：异常要统一兜底、不泄露堆栈、区分参数错误(400)与服务错误(500)；日志要记耗时（AI 是慢操作）、记长度而非全文（防刷屏泄露）、带 traceId 便于追踪、注意脱敏。

---

## 本章练习答案

**思考题 1 参考答案**：
"可选 + 全局默认"的好处：①调用方省心，多数场景不传也能用；②角色可按需覆盖，一套接口支持多种人格，复用性强；③Prompt 由后端集中管理，可统一优化、审计、防注入。相比前端写死：后端管控更安全（前端可被篡改）、可热更新（改后端不用发前端版本）、可做 A/B 测试与统一治理。这是"策略集中于服务端"的工程原则在 AI 场景的体现。

**思考题 2 参考答案**：
Temperature 控制生成随机性。舆情分析、数学计算要的是**确定、可复现、可信**的结果——同样输入应得同样输出，故设接近 0。写文案要的是**多样、有创意、不重复**，故设高一些，让模型在候选词里更"放得开"。本质是：需要正确性时压低随机性，需要创造性时释放随机性。

**思考题 3 参考答案**：
不直接抛原始异常给前端，原因有三：①**安全**——原始异常常含底层堆栈、SDK 细节甚至内部信息，泄露有风险；②**统一**——包装成业务异常后由全局 `@RestControllerAdvice` 统一转成规范的 `Result` 结构，前端处理一致；③**可控**——包装时可补充业务语义（"AI 服务调用失败：..."），比原始技术异常更友好。这与传统项目"不把 SQLException 直接抛给前端"是同一套工程习惯。

---

## 企业应用小结

这个"五脏俱全"的 Demo，本质是一个**可上生产的 AI 接口骨架**：分层清晰、参数校验、角色可配、参数可调、异常兜底、日志可观测。它虽小，却涵盖了 AI 应用最核心的调用范式。你未来所有的 Agent 功能——多轮记忆、流式输出、结构化输出、工具调用——都会在这个骨架上生长。**把这一章代码亲手敲一遍、跑通、玩透，你就真正迈进了 AI Agent 工程师的大门**。

---

> ✅ **本章完成。** 最后一章《企业级 AI 应用架构》，我们将跳出单个 Demo，从架构师视角看：一个真正的商业级 AI Agent 平台是怎样从这个骨架演进而来的——涉及分层架构、模型网关、成本控制、限流熔断、可观测性、安全合规等企业级议题，为后续 29 天实战建立全局认知地图。
>
> **请先完成本章三道思考题，并亲手把 Demo 跑通，完成后告诉我，我们进入第六章。**