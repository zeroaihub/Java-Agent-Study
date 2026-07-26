# 第四章：Streaming 流式输出

> 《30 天打造商业级 AI Agent 平台（Java 版）》· Day02 · 第四章
>
> 五段式教学模板：**为什么学 → 是什么 → 怎么用 → 用在哪 → 避坑优化**。
> 本章讲透 SSE / chunk / delta / 打字机效果与 Spring AI 的 Flux 流式实现。学完请完成章末任务：
> **「为什么流式输出能显著提升用户体验？请结合首字延迟与感知延迟解释」**。

---

## 第一部分：为什么学（核心价值）

### 1. 为什么必须懂流式输出？

回忆第二章：LLM 是**自回归逐 Token 生成**的。一段 300 字的回答，模型要一个 Token 一个 Token 地「吐」出来，可能耗时 5～15 秒。

现在有两种把结果给用户的方式：

- **非流式（阻塞式）**：等模型把 300 字**全部生成完**，再一次性返回。用户盯着转圈的加载动画等 10 秒，然后「唰」地全出来。
- **流式（Streaming）**：模型每生成几个字，就**立刻推给前端**，用户看到文字像打字机一样一个个蹦出来。

同样是 10 秒生成完，**流式的体验好了不止十倍**。因为：

> 用户第一次看到字（**首字延迟 TTFT，Time To First Token**）从 10 秒降到了 0.5 秒。

人对「等待」的痛苦，主要来自**不确定感**。流式让用户第一时间确认「AI 在干活了」，感知延迟大幅下降。

### 2. 为什么这是商业级 Agent 的标配？

打开 ChatGPT、Claude、通义千问、Kimi——**没有一个不是流式输出的**。这不是炫技，是刚需：

- **长回答场景**：写代码、写文案、写方案，动辄上千 Token，非流式用户根本等不住。
- **留存与转化**：首字延迟每增加 1 秒，用户流失率显著上升。
- **可中断**：流式下用户可以「觉得答歪了就停」，节省 Token 成本。

**结论：不会做流式输出的 AI 工程师，做不出能上线的产品。**

### 3. 为什么 Java 工程师容易在这里踩坑？

传统 Java Web 是**请求-响应**模型：一个 HTTP 请求进来，处理完一次性返回。而流式是**一个请求，持续返回多段数据**，这需要：

- 异步、非阻塞的 IO（Spring WebFlux / `Flux`）。
- 特殊的 HTTP 协议（SSE，Server-Sent Events）。
- 前端配合逐段渲染。

很多 Java 工程师第一次接触 `Flux`、`SSE` 会懵。本章就帮你打通这条链路。

---

## 第二部分：是什么（核心概念拆解）

### 1. SSE（Server-Sent Events）是什么？

SSE 是一种**服务器向客户端单向持续推送数据**的 HTTP 技术。

| 对比项 | 普通 HTTP | SSE | WebSocket |
|--------|-----------|-----|-----------|
| 方向 | 请求→响应，一次 | 服务器→客户端，持续 | 双向 |
| 协议 | HTTP | HTTP（长连接） | ws://（独立协议） |
| 复杂度 | 低 | 低 | 高 |
| 适用场景 | 普通接口 | **AI 流式、消息通知** | 聊天室、实时协作 |

**关键点：LLM 流式输出用 SSE 就够了**，因为它只需要「服务器单向往外推」，不需要客户端持续往服务器发。用 WebSocket 是杀鸡用牛刀。

SSE 的响应头特征：

```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

SSE 的数据格式（每段以 `data:` 开头，`\n\n` 结尾）：

```
data: {"choices":[{"delta":{"content":"你"}}]}

data: {"choices":[{"delta":{"content":"好"}}]}

data: [DONE]
```

### 2. chunk（数据块）是什么？

流式响应不是一次返回一个完整 JSON，而是返回**一连串小 JSON**，每一个叫一个 **chunk（块）**。

- 非流式返回 1 个大对象：`choices[0].message.content = "你好，世界"`
- 流式返回 N 个 chunk，每个 chunk 里只带**新增的那一小段**。

### 3. delta（增量）是什么？

这是流式最核心的字段。非流式里是 `message.content`（完整内容），**流式里换成了 `delta.content`（增量内容）**。

对比一眼看懂：

```jsonc
// 非流式：一个 choice 里是完整的 message
{
  "choices": [
    { "message": { "role": "assistant", "content": "你好，世界" } }
  ]
}

// 流式：每个 chunk 的 choice 里是 delta（只有新增部分）
// chunk 1
{ "choices": [ { "delta": { "role": "assistant", "content": "你" } } ] }
// chunk 2
{ "choices": [ { "delta": { "content": "好" } } ] }
// chunk 3
{ "choices": [ { "delta": { "content": "，世界" } } ] }
// 最后一个 chunk：delta 为空，finish_reason 有值
{ "choices": [ { "delta": {}, "finish_reason": "stop" } ] }
```

> **前端要做的事**：把每个 chunk 的 `delta.content` **不断拼接（累加）**，就得到完整回答。这就是「打字机效果」的本质。

### 4. `[DONE]` 结束标记是什么？

OpenAI 协议里，流式结束时会额外发一行 `data: [DONE]`，告诉客户端「说完了，可以关闭连接了」。这不是 JSON，是纯文本标记，解析时要特判。

### 5. Spring AI 里的流式是什么？

Spring AI 用 **Project Reactor 的 `Flux<T>`** 表达流式。

- `Mono<T>`：0 或 1 个元素（对应非流式，一次返回）。
- `Flux<T>`：0 到 N 个元素的**异步数据流**（对应流式，持续返回）。

所以，非流式用 `.call()` 返回一次，**流式用 `.stream()` 返回 `Flux<String>`**。

---

## 第三部分：怎么用（实战演练）

### 实验 1：用 curl 直接看流式原始数据

```bash
curl -N http://127.0.0.1:1234/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-1234" \
  -d '{
    "model": "huihui-qwen3.5-9b-abliterated-mlx",
    "stream": true,
    "messages": [
      {"role": "user", "content": "用一句话介绍你自己"}
    ]
  }'
```

> 注意 `curl` 的 `-N` 参数（`--no-buffer`），关闭缓冲，才能看到数据一段段刷出来。你会看到屏幕上不断打印 `data: {...}`，最后一行是 `data: [DONE]`。

`stream: true` 是唯一开关。加了它，服务端就以 SSE 方式吐数据。

### 实验 2：Spring AI 的 `.stream()` 用法（核心范式）

对比第二章的非流式写法，流式只改**最后一步**：

```java
// 非流式：.call().content() → 返回 String（等全部生成完）
String answer = chatClient.prompt()
        .user("介绍一下 Spring AI")
        .call()
        .content();

// 流式：.stream().content() → 返回 Flux<String>（每段一个元素）
Flux<String> stream = chatClient.prompt()
        .user("介绍一下 Spring AI")
        .stream()
        .content();
```

`Flux<String>` 里的每个 `String`，就是一个 chunk 的 `delta.content`。你**不需要自己拼接**，Spring AI 已经帮你抽取好增量文本，你只管把它推给前端即可。

### 实验 3：Controller 层暴露 SSE 接口

在 Spring WebFlux / Spring MVC 里，返回 `Flux<T>` 并声明 `produces = MediaType.TEXT_EVENT_STREAM_VALUE`，Spring 会自动帮你按 SSE 协议往外推：

```java
@GetMapping(value = "/api/day02/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<String> stream(@RequestParam String message) {
    return chatClient.prompt()
            .user(message)
            .stream()
            .content();
}
```

- `produces = TEXT_EVENT_STREAM_VALUE` → 告诉 Spring 用 `text/event-stream`（SSE）返回。
- 返回类型是 `Flux<String>` → Spring 逐个元素封装成 `data: ...\n\n` 推给前端。

> 本章只讲原理与范式，**完整可运行的流式 Service / Controller 代码，将在第五章统一落地**。

### 实验 4：前端如何消费 SSE（原理示意）

```javascript
const es = new EventSource('/api/day02/chat/stream?message=你好');
let answer = '';
es.onmessage = (e) => {
  if (e.data === '[DONE]') { es.close(); return; }
  answer += e.data;            // 关键：不断累加 delta
  render(answer);              // 每来一段就重绘，形成打字机效果
};
es.onerror = () => es.close(); // 出错要主动关闭，否则浏览器会自动重连
```

浏览器原生的 `EventSource` 就是专门消费 SSE 的 API，天生支持自动重连。

---

## 第四部分：用在哪（10 个真实场景）

1. **AI 对话产品**：ChatGPT 式打字机效果，长回答不卡顿，用户体验拉满。
2. **代码生成器**：生成上百行代码时，边生成边展示，用户可提前预览。
3. **文案 / 报告生成**：营销文案、周报、方案书，边写边看，随时叫停。
4. **实时翻译**：长段落翻译，逐句流出，不用干等全文。
5. **智能客服**：回答长政策条款时，先出首句安抚用户，降低感知等待。
6. **AI 搜索（如 Perplexity）**：一边检索一边总结流出，体验丝滑。
7. **语音助手前置**：流式文本可对接流式 TTS，边生成文字边合成语音，端到端低延迟。
8. **教育 / 解题**：逐步展示解题过程，模拟老师一步步板书。
9. **可中断的批量任务**：用户觉得跑偏了随时点停，省下后续 Token 费用。
10. **多 Agent 协作展示**：把某个 Agent 的思考过程流式展示给用户，增强可解释性与信任。

---

## 第五部分：避坑优化（10 条企业级红线）

1. **别忘了 `-N` / 关闭缓冲**：本地测 curl 看不到流式，八成是缓冲没关；线上 Nginx 未关 `proxy_buffering` 会把流式「攒成一坨」再返回，必须 `proxy_buffering off;`。
2. **别用普通 `RestController` 阻塞返回 `Flux`**：确认引入 `spring-boot-starter-webflux`，且返回类型是 `Flux`，`produces` 声明 `text/event-stream`，否则退化成一次性返回。
3. **异步超时要配够**：流式连接可能持续几十秒，`spring.mvc.async.request-timeout` 太短会中途断流（本项目 `application.yml` 已配 600000ms）。
4. **一定要处理 `[DONE]` 与结束**：前端不特判 `[DONE]` 会把结束标记也拼进正文；后端 `Flux` 结束要正常 `complete()`，避免连接悬挂。
5. **错误必须能流式返回或优雅降级**：生成中途报错（限流、超时），要在流里推一个错误事件或 fallback 文案，不能让前端「卡在半句话」。
6. **Token 计费在流式下要单独统计**：流式的 `usage` 通常在最后一个 chunk 才有（部分厂商甚至不返回），别以为流式就不花钱，成本一分不少。
7. **别在流式回调里做重 IO / 阻塞操作**：`Flux` 的响应式线程里做数据库同步查询、大文件读写，会拖垮整条流甚至阻塞 EventLoop，用异步或 `subscribeOn` 隔离。
8. **背压（Backpressure）意识**：前端消费慢、网络差时，服务端产出快，要让 Reactor 的背压机制生效，别无脑缓存全部 chunk 撑爆内存。
9. **连接泄漏防护**：用户关页面 / 断网时要能感知并释放连接（`doOnCancel` / `doFinally`），否则大量僵死连接拖垮服务。
10. **流式内容也要过安全审核**：不能因为「一段段出」就跳过内容合规校验，敏感词、越权信息同样要拦，必要时做「边流边审、命中即断」。

---

## 本章面试高频题（7 道）

1. **流式输出和非流式输出的本质区别是什么？** 参考：非流式等全部生成完一次返回（`message.content`），流式逐 chunk 返回增量（`delta.content`）；核心差异在**首字延迟（TTFT）**和感知体验。
2. **SSE、WebSocket、长轮询怎么选？** 参考：AI 单向推送用 SSE 最合适（基于 HTTP、简单、自动重连）；双向实时用 WebSocket；长轮询是过时兜底方案。
3. **chunk 里的 `delta` 和非流式的 `message` 有什么关系？** 参考：`delta` 是增量片段，把所有 chunk 的 `delta.content` 按序拼接 = 非流式的完整 `message.content`。
4. **Spring AI 里 `Mono` 和 `Flux` 的区别？流式用哪个？** 参考：`Mono` 0/1 个元素（非流式），`Flux` 0～N 个元素的异步流（流式），流式用 `.stream()` 返回 `Flux`。
5. **流式接口线上「不流」了，一坨返回，可能是什么原因？** 参考：Nginx `proxy_buffering` 未关、网关缓冲、`produces` 未声明 SSE、返回类型不是 `Flux`、异步超时过短。
6. **流式场景下如何统计 Token 成本？** 参考：`usage` 一般在最后一个 chunk 返回；部分厂商不返回需自行按响应文本估算；流式与非流式计费规则一致，成本不减。
7. **流式连接如何防止资源泄漏？** 参考：监听取消 / 断连事件（`doOnCancel`、`doFinally`）主动释放；配合超时与背压；网关侧配置合理的连接空闲回收。

---

## 章末任务 ✅

> **请回答：为什么流式输出能显著提升用户体验？请结合「首字延迟（TTFT）」与「感知延迟」两个概念解释，并说明它对长回答场景的意义。**

请把你的答案写在下面的练习区，我会点评并纠正：

### 我的答案（练习区）

```text
（在这里写下你的思考……）
```

---

> 下一章预告：**第五章 · 用 Spring AI 实现 Chat 服务**——我们将把前四章的原理全部落地成**完整可运行的 Java 代码**：分层架构、非流式接口、流式接口、多轮会话，一次写透。