# 第六章：企业级优化

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day02 · 第六章
>
> 五段式教学模板：**为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化**。
> 本章把第五章「能跑」的代码，升级为「能扛生产流量」的服务：日志、异常、配置外置、超时重试、限流降级、可观测性。
> 学完请完成章末任务：**「为你的 Day02 Chat 服务设计一套上线前检查清单（Checklist）」**。

---

## 第一部分：为什么学（核心价值）

### 1. 为什么「能跑」离「能上线」还差十万八千里？

第五章的代码，本地一测——通了，很爽。但直接丢到生产，你会在**凌晨三点被报警叫醒**：

- 模型服务抖动一下，你的接口全线超时，用户看到白屏。
- 有人恶意刷接口，Token 费用一天烧掉几千块。
- 出了问题想查日志，发现**什么都没记**，两眼一抹黑。
- 想改个 Prompt / 换个模型，得改代码、重新打包、重新发版。

**「能跑」是功能正确，「能上线」是在各种异常、高并发、故障下依然稳定、可控、可观测。** 这一章讲的就是这中间的鸿沟。

### 2. 为什么这是区分「初级」和「高级」工程师的分水岭？

初级工程师交付「功能」，高级工程师交付「可运维的系统」。面试时，功能谁都会写，但一问「你的 AI 接口怎么做限流降级？成本怎么控制？出故障怎么排查？」——**能答上来的，才是能拿高薪的人。**

AI 应用尤其特殊：**每一次调用都在花真金白银（Token 费）**，且依赖的模型服务不稳定、延迟高。所以企业级优化在 AI 场景比传统接口更重要。

---

## 第二部分：是什么（六大企业级能力拆解）

### 1. 日志（Logging）

不是随便 `System.out.println`，而是**结构化、分级、可追踪**的日志：

- **分级**：`DEBUG / INFO / WARN / ERROR`，生产默认 INFO。
- **可追踪**：每个请求带一个 `traceId`（链路 ID），一次调用的所有日志能串起来。
- **关键埋点**：请求入参（脱敏）、模型耗时、Token 用量、异常堆栈。

### 2. 异常处理（Exception Handling）

第五章有了基础异常处理器，企业级还要：

- **分类异常**：区分「参数错误（4xx）」「模型服务错误（5xx）」「限流（429）」，返回不同状态码。
- **友好降级**：模型挂了返回兜底文案（如「AI 正忙请稍后重试」），而不是把堆栈甩给用户。
- **不吞异常**：降级的同时必须 `ERROR` 日志记录真实原因，方便排查。

### 3. 配置外置（Externalized Configuration）

把「会变的东西」从代码里拿出来，放进 `application.yml` / 配置中心：

- 模型 `base-url`、`model`、`temperature`、超时时间。
- System Prompt（第三章的「Prompt 工程化」）。
- 限流阈值、降级开关。

> **好处**：改配置不用改代码、不用重新编译，甚至配置中心可以热更新。

### 4. 超时与重试（Timeout & Retry）

- **超时**：模型不能无限等，超过阈值（如 30s）就中断，避免线程被占满。本项目 `application.yml` 已配 `timeout: 600000`（长文本场景可调）。
- **重试**：网络抖动、偶发 5xx，自动重试 1～2 次（带退避），提升成功率。**注意幂等**：重试可能重复计费，非流式可试，流式重试要谨慎。

### 5. 限流与降级（Rate Limiting & Fallback）

- **限流**：控制单位时间的请求量 / Token 量，防止被刷爆、防止成本失控。常用令牌桶（如 Resilience4j、Guava RateLimiter）。
- **降级**：依赖不可用时，主动返回兜底结果，保护系统不雪崩（熔断 Circuit Breaker）。

### 6. 可观测性（Observability）

「三大支柱」：

- **Metrics（指标）**：QPS、P99 延迟、成功率、Token 消耗，用 Micrometer + Prometheus + Grafana。
- **Logging（日志）**：见上。
- **Tracing（链路追踪）**：一次请求跨多个服务的完整链路，用于定位慢在哪。

---

## 第三部分：怎么用（落地要点与代码范式）

> 本章的企业级增强，将在配套代码里体现为：日志 AOP / 拦截器、增强版异常处理器、外置配置项、超时重试配置。以下讲清关键范式。

### 1. 结构化日志 + traceId

```java
@Slf4j
@Service
public class Day02ChatServiceImpl implements Day02ChatService {
    @Override
    public ChatResponse chat(ChatRequest req) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        long start = System.currentTimeMillis();
        log.info("[{}] chat 请求, msgLen={}", traceId, req.getMessage().length()); // 只记长度，不记原文（脱敏）
        try {
            var resp = /* 调用模型 */ ...;
            log.info("[{}] chat 成功, 耗时={}ms, totalTokens={}",
                    traceId, System.currentTimeMillis() - start, resp.tokens());
            return resp;
        } catch (Exception e) {
            log.error("[{}] chat 失败, 耗时={}ms", traceId, System.currentTimeMillis() - start, e);
            throw e;
        }
    }
}
```

要点：**记长度不记原文**（脱敏）、**记耗时和 Token**（成本与性能）、**异常带 traceId**（可追踪）。

### 2. 分类异常处理器（升级版）

```java
@Slf4j
@RestControllerAdvice(basePackages = "com.zero.ai.agentstudy.day02llmapi.controller")
public class Day02ExceptionHandler {

    // 参数校验 → 400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValid(MethodArgumentNotValidException e) {
        return R.fail(400, "参数错误：" + e.getBindingResult().getFieldError().getDefaultMessage());
    }

    // 模型 / 上游服务异常 → 502 + 友好降级
    @ExceptionHandler({ ResourceAccessException.class, RestClientException.class })
    public R<Void> handleUpstream(Exception e) {
        log.error("上游模型服务异常", e);          // 记录真实原因
        return R.fail(502, "AI 服务繁忙，请稍后重试"); // 返回友好文案
    }

    // 兜底 → 500
    @ExceptionHandler(Exception.class)
    public R<Void> handleAll(Exception e) {
        log.error("未预期异常", e);
        return R.fail(500, "服务异常，请联系管理员");
    }
}
```

### 3. 配置外置（`application.yml`）

```yaml
day02:
  chat:
    default-system-prompt: "你是 Day02 AI 助手，专业、严谨、友好。"
    default-temperature: 0.7
    timeout-ms: 30000
    retry:
      max-attempts: 2
    rate-limit:
      permits-per-second: 10   # 每秒最多 10 次请求
```

配合 `@ConfigurationProperties(prefix = "day02.chat")` 绑定成一个配置类，Service 注入使用。**改行为只改 yml，不动代码。**

### 4. 限流（Guava RateLimiter 范式）

```java
private final RateLimiter rateLimiter = RateLimiter.create(10.0); // 每秒 10 个令牌

public ChatResponse chat(ChatRequest req) {
    if (!rateLimiter.tryAcquire()) {
        throw new BizException(429, "请求太频繁，请稍后再试");
    }
    // ... 正常调用
}
```

### 5. 超时与重试（Spring Retry 范式）

```java
@Retryable(
    retryFor = { ResourceAccessException.class },
    maxAttempts = 2,
    backoff = @Backoff(delay = 500))   // 失败后延迟 500ms 再重试
public ChatResponse callModel(ChatRequest req) { ... }

@Recover  // 重试全失败后的兜底
public ChatResponse recover(ResourceAccessException e, ChatRequest req) {
    return ChatResponse.fallback("AI 暂时不可用，请稍后重试");
}
```

### 6. 指标埋点（Micrometer 范式）

```java
meterRegistry.counter("day02.chat.requests").increment();          // 请求计数
meterRegistry.timer("day02.chat.latency").record(() -> callModel());// 延迟
meterRegistry.counter("day02.chat.tokens").increment(totalTokens); // Token 消耗
```

---

## 第四部分：用在哪（10 个真实场景）

1. **成本管控**：通过 Token 指标 + 限流，把每日 AI 花费控制在预算内。
2. **故障排查**：线上报错，靠 traceId 一路串起日志，5 分钟定位问题。
3. **大促保护**：流量高峰时限流 + 熔断，保住核心链路不被 AI 接口拖垮。
4. **多环境部署**：dev / test / prod 用不同配置（模型、阈值），一套代码多处跑。
5. **灰度发布**：配置中心动态切换模型 / Prompt，无需发版即可 A/B。
6. **SLA 保障**：监控 P99 延迟与成功率，达不到指标自动告警。
7. **安全合规**：日志脱敏，避免用户隐私 / API Key 泄漏进日志系统。
8. **稳定性演练**：模拟模型服务宕机，验证降级文案是否生效（混沌工程）。
9. **容量规划**：靠 QPS / 延迟指标做扩容决策，提前加机器。
10. **计费与结算**：按 Token 指标给不同业务方 / 租户分摊 AI 成本。

---

## 第五部分：避坑优化（10 条企业级红线）

1. **日志记原文泄密**：把用户完整问题 / 回答打进日志，可能违反隐私合规，务必脱敏（记长度 / 摘要 / hash）。
2. **无 traceId 无法排查**：没有链路 ID，生产出问题只能靠猜，务必每请求生成并贯穿。
3. **重试导致重复计费**：非幂等操作盲目重试，Token 费翻倍，流式重试还可能吐重复内容，要评估幂等性。
4. **超时设太长或太短**：太短长文本被截断，太长线程被占满拖垮服务，要按业务分档配置。
5. **限流阈值拍脑袋**：不做压测直接设阈值，要么误杀正常流量，要么形同虚设，要基于实测 QPS 设定。
6. **降级文案「假成功」**：降级返回兜底文案却用 `code=200`，让上游以为成功，应返回明确的降级标识 / 状态码。
7. **异常被吞**：`catch` 后不打日志直接返回友好文案，真实原因永远查不到，降级必须同时 `ERROR` 记录。
8. **配置硬编码回退**：号称配置外置，代码里却留了硬编码默认值且优先级更高，改 yml 不生效。
9. **指标无告警**：埋了 Metrics 却没配告警规则，等于没监控，出问题还是靠用户投诉才知道。
10. **没做上线 Checklist**：凭感觉上线，漏配超时 / 限流 / 日志，全靠运气，必须有标准化发布清单。

---

## 本章面试高频题（7 道）

1. **AI 接口如何做成本控制？** 参考：限流（令牌桶）控请求量、采集 Token 指标做预算告警、缓存高频问答、按 `max_tokens` 限制单次输出。
2. **超时和重试怎么配才合理？重试有什么风险？** 参考：按业务分档配超时；重试针对偶发网络 / 5xx，带退避且限次；风险是重复计费与非幂等副作用。
3. **限流和熔断（降级）的区别？** 参考：限流控「入口流量」防过载；熔断在「依赖故障」时快速失败并降级，防雪崩，二者常配合使用。
4. **一次线上 AI 接口超时，你怎么排查？** 参考：靠 traceId 串日志 → 看是网络 / 模型生成慢 / 线程池满 → 结合 Metrics 的 P99 与依赖延迟定位。
5. **可观测性的三大支柱是什么？** 参考：Metrics（指标）、Logging（日志）、Tracing（链路追踪）。
6. **为什么要配置外置？AI 场景哪些该外置？** 参考：改行为不改代码、支持多环境与热更新；base-url / model / temperature / 超时 / 限流阈值 / System Prompt 都应外置。
7. **日志脱敏怎么做？为什么重要？** 参考：只记长度 / 摘要 / 掩码，避免隐私与密钥泄漏，满足合规（如 GDPR / 个保法）。

---

## 章末任务 ✅

> **请为你的 Day02 Chat 服务设计一套「上线前检查清单（Checklist）」**，至少覆盖：日志与脱敏、异常与降级、配置外置、超时重试、限流、监控告警六个维度，每个维度写 1～2 条具体检查项。

### 我的答案（练习区）

```text
上线前 Checklist：
1. 日志：______
2. 异常降级：______
3. 配置外置：______
4. 超时重试：______
5. 限流：______
6. 监控告警：______
```

---

> 🎉 恭喜！你已完成 Day02 全部六章的理论学习。接下来我将把第五、六章的代码**真实落地到项目**，并交付 `ARCHITECTURE.md`（架构文档）与 `TODO.md`（练习任务），让你能亲手跑通、动手改造这套 AI Chat Service V1。