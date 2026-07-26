# Day02 动手任务清单：AI Chat Service V1

> 按难度分级：⭐ 必做（打基础）｜⭐⭐ 进阶（练工程）｜⭐⭐⭐ 企业挑战（拔高）。
> 建议顺序完成，每完成一项在 `[ ]` 中打勾。

---

## ⭐ 必做（跑通三大能力）

- [ ] **启动服务**：确认本地大模型在 `127.0.0.1:1234` 就绪，`mvn spring-boot:run` 启动，端口 8080。
- [ ] **非流式对话**：用 curl 调 `/api/day02/chat`，观察返回的 `content` 与 `totalTokens`。
  ```bash
  curl -X POST http://localhost:8080/api/day02/chat \
    -H "Content-Type: application/json" \
    -d '{"message":"用一句话介绍你自己"}'
  ```
- [ ] **流式对话**：调 `/api/day02/chat/stream`，用 `-N` 关闭缓冲，观察逐字返回（打字机效果）。
  ```bash
  curl -N -X POST http://localhost:8080/api/day02/chat/stream \
    -H "Content-Type: application/json" \
    -d '{"message":"写一首关于秋天的短诗"}'
  ```
- [ ] **多轮会话**：用同一 `conversationId` 连续调 `/api/day02/chat/multi` 两次，验证第二轮能记住第一轮内容。
  ```bash
  curl -X POST http://localhost:8080/api/day02/chat/multi \
    -H "Content-Type: application/json" \
    -d '{"message":"我叫小明","conversationId":"c1"}'
  # 再问：
  curl -X POST http://localhost:8080/api/day02/chat/multi \
    -H "Content-Type: application/json" \
    -d '{"message":"我叫什么名字？","conversationId":"c1"}'
  ```
- [ ] **参数校验**：发一个 `message` 为空的请求，确认返回 400 且提示清晰。
- [ ] **自定义 systemPrompt / temperature**：传入 `systemPrompt` 和 `temperature`，观察风格变化。

---

## ⭐⭐ 进阶（工程化打磨）

- [ ] **限流验证**：把 `day02.chat.permits-per-second` 调到 1，快速连发多个请求，确认触发 429。
- [ ] **配置外置**：在 `application.yml` 加 `day02.chat.*` 覆盖默认值，验证生效：
  ```yaml
  day02:
    chat:
      default-temperature: 0.3
      permits-per-second: 5
      max-history-size: 10
  ```
- [ ] **会话重置接口**：给 `ConversationStore.clear` 补一个 `DELETE /api/day02/chat/{conversationId}` 接口。
- [ ] **窗口裁剪验证**：把 `max-history-size` 设小，多轮对话后确认旧消息被裁剪。
- [ ] **traceId 贯通**：在日志中找到同一次请求的 traceId，理解链路串联的意义。
- [ ] **Token 成本核算**：在返回中读取 usage，估算单次调用成本，思考如何做用量监控。

---

## ⭐⭐⭐ 企业挑战（生产级演进）

- [ ] **Redis 会话存储**：把 `ConversationStore` 换成 Redis 实现，加 TTL 过期，解决重启丢失 + 水平扩展问题。
- [ ] **分布式限流**：用 Redis + Lua 或 Sentinel 替换 `SimpleRateLimiter`，支持多实例统一限流。
- [ ] **重试与熔断**：接入 Resilience4j，对上游超时做重试，连续失败触发熔断并降级。
- [ ] **可观测性**：接入 Micrometer + Prometheus + Grafana，暴露 QPS/耗时/Token 指标看板。
- [ ] **流式 + 多轮组合**：实现「流式多轮」——边流式输出边把完整回答写回会话历史。
- [ ] **多模型路由**：抽象 Provider 接口，按成本或能力把请求路由到不同模型。
- [ ] **压测**：用 wrk/JMeter 压测三个接口，观察限流、超时、内存增长，写一份性能报告。

---

## 自检清单（提交前对照）

- [ ] 三个接口均能正常返回，异常场景返回码正确（400/429/502/500）。
- [ ] 未修改任何 Day01 代码，服务能正常启动无 Bean 冲突。
- [ ] 日志脱敏（不打印用户消息原文），含 traceId 与耗时。
- [ ] 理解内存会话的三大生产风险（重启丢失 / 无法水平扩展 / 内存泄漏）。