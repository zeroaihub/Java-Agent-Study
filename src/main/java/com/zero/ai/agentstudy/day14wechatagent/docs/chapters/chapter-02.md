# 第二章 消息模型与消息管线（Message & Message Pipeline）

> 本章代码位于 `day14wechatagent/wechatcore`，是整个 WeChat Agent 的地基。
> 读完本章，你将能独立设计一条**生产级、可插拔、幂等、可观测**的 IM 消息处理管线。

---

## 一、为什么要学：消息是 Agent 世界的「第一公民」

在 Day1~Day13，我们的输入几乎都来自 HTTP 请求体或本地调用——**结构规整、来源单一、语义明确**。但当 Agent 的入口变成企业微信这样的 IM 平台，输入的形态彻底变了：

1. **来源异构**：同一个 Agent 要同时接企业微信、Webhook、飞书、钉钉，每个平台的消息 JSON 结构、加密方式、回调协议都不一样。
2. **投递不可靠**：IM 网关普遍是「至少一次（at-least-once）」投递，同一条消息会被重复推送。如果不做幂等，用户发一句「帮我订会议室」，Agent 可能订三次。
3. **形态多样**：文本、图片、文件、语音、卡片……单聊要处理，群聊还要判断「机器人有没有被 @」。
4. **强多租户**：一套系统服务多个企业客户，`tenantId` 必须贯穿每一条消息，否则就是数据越权的重大事故。

如果我们像写 Demo 那样，在 Controller 里直接 `if (channel == "wecom")` 一路 `if-else` 到底，三个月后这段代码就会变成没人敢碰的「意大利面」。

> **本章的核心命题**：如何用领域模型 + 六边形架构，把「渠道的脏乱差」隔离在系统边缘，让核心业务永远面对一个**干净、统一、不可变**的消息对象。

---

## 二、是什么：消息模型与消息管线的定义

### 2.1 两个核心值对象

| 对象 | 角色 | 关键特征 |
| --- | --- | --- |
| `InboundMessage` | 入站消息（聚合根） | 不可变、携带幂等键、渠道无关 |
| `OutboundMessage` | 出站消息 | 不可变、默认 Markdown、携带同一 traceId |

**为什么必须不可变（immutable）？** 一条消息在管线里会流经「去重→鉴权→解析→路由→编排→回复」至少 6 个环节。如果任何一个环节能修改它，排查问题时你将无法判断「究竟是谁改了 tenantId」。不可变对象 + Builder，是并发管线里最省心的选择。

参见 [`InboundMessage`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/domain/model/InboundMessage.java:31) 的字段设计——每个字段都是 `final`。

### 2.2 什么是消息管线（Message Pipeline）

消息管线是一条**单向、分阶段、可观测**的处理流水线。企业级实现通常划分为 8 个阶段：

```
接收(Receive) -> 去重(Dedup) -> 鉴权(Auth) -> 解析(Parse)
      -> 路由(Route) -> 编排(Orchestrate) -> 回复(Reply) -> 审计(Audit)
```

每个阶段职责单一、可独立测试、可独立埋点。这正是「消息级 Trace」和「Token 成本核算」得以实现的前提。

### 2.3 六边形架构中的位置

```
                 +-----------------------------+
入站适配器   -->  |  应用层(管线编排)              |  --> 出站端口 MessageChannel
(企微/Webhook)   |  领域层(InboundMessage 等)    |      (WeCom/Webhook/Mock)
                 +-----------------------------+
                            |
                     出站端口 IdempotencyStore
                        (Redis / 内存)
```

- **入站适配器**：把渠道协议翻译成 `InboundMessage`（第八章讲 Webhook 时落地）。
- **出站端口** [`MessageChannel`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/domain/port/MessageChannel.java:19)：领域层只依赖这个接口，不认识任何具体渠道。
- **幂等端口** [`IdempotencyStore`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/domain/port/IdempotencyStore.java:17)：抽象「首次写入」语义，Redis 或内存都能实现。

---

## 三、怎么用：核心代码逐行解析

### 3.1 值对象枚举：把「魔法字符串」关进笼子

[`ChannelType`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/domain/model/ChannelType.java) 与 [`MessageType`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/domain/model/MessageType.java) 都提供了 `of(code)` 解析方法，且**未知值不抛异常而是回退默认值**：

```java
public static MessageType of(String code) {
    if (code == null || code.isBlank()) return TEXT;
    for (MessageType t : values()) {
        if (t.code.equalsIgnoreCase(code.trim())) return t;
    }
    return TEXT; // 未知类型回退文本，保证管线不因新消息类型而崩溃
}
```

> **设计意图**：IM 平台随时可能新增消息类型（比如「小程序卡片」）。如果解析到未知类型就抛异常，等于把平台的升级变成我们的线上事故。**容错回退**是渠道适配层的黄金法则。

### 3.2 入站消息：幂等键与「是否处理」的领域逻辑

[`InboundMessage`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/domain/model/InboundMessage.java:79) 有两个关键领域方法：

```java
public boolean shouldHandle() {          // 群聊仅当被 @ 才处理，避免刷屏
    if (chatType == ChatType.SINGLE) return true;
    return mentioned;
}

public String idempotencyKey() {         // {tenantId}:{msgId} 组合去重
    return tenantId + ":" + msgId;
}
```

**为什么幂等键要带 tenantId？** 不同企业客户的消息 ID 可能碰撞（各自平台独立生成）。只用 `msgId` 去重，会导致 A 公司的消息误杀 B 公司的消息。**多租户系统里，几乎所有唯一性约束都要带上 tenantId。**

### 3.3 出站消息：静态工厂降低调用方心智负担

[`OutboundMessage`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/domain/model/OutboundMessage.java:63) 提供了 `markdownReply(inbound, md)` 与 `textReply(inbound, text)` 两个静态工厂，自动从入站消息派生「回给谁、回哪个会话、traceId 是多少」：

```java
public static OutboundMessage markdownReply(InboundMessage inbound, String markdown) {
    return OutboundMessage.builder()
            .tenantId(inbound.getTenantId())
            .channelType(inbound.getChannelType())
            .conversationId(inbound.getConversationId())
            .toUserId(inbound.getFromUserId())
            .messageType(MessageType.MARKDOWN)
            .content(markdown)
            .traceId(inbound.getTraceId())   // 关键：请求-回复共享 traceId
            .build();
}
```

> **traceId 贯穿**：入站与出站共享同一个 traceId，你才能在日志里用一个 ID 串起「用户问了什么 -> Agent 怎么想的 -> 回复了什么」的完整链路。这是可观测性的基石。

### 3.4 渠道注册中心：Strategy + Registry 消灭 if-else

[`MessageChannelRegistry`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/application/MessageChannelRegistry.java:24) 在启动时收集所有 `MessageChannel` Bean，按类型建索引：

```java
public MessageChannelRegistry(List<MessageChannel> messageChannels) {
    for (MessageChannel channel : messageChannels) {
        channels.put(channel.channelType(), channel);
    }
}

public void dispatch(OutboundMessage message) {
    MessageChannel channel = resolve(message.getChannelType());
    if (!channel.supports(message.getTenantId())) {
        throw new WeChatAgentException(ErrorCode.TENANT_FORBIDDEN, ...);
    }
    channel.send(message);
}
```

> **可插拔的真正含义**：新增「飞书」渠道时，你只需写一个 `FeishuMessageChannel implements MessageChannel` 并标注 `@Component`。注册中心、应用层、领域层**一行都不用改**。这就是开闭原则（OCP）在架构层面的体现。

### 3.5 幂等存储：一个 `compute` 搞定原子抢占

本地实现 [`InMemoryIdempotencyStore`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/infrastructure/InMemoryIdempotencyStore.java:26) 用 `ConcurrentHashMap.compute` 实现原子的「首次写入」：

```java
store.compute(key, (k, existing) -> {
    if (existing == null || existing <= now) {  // 不存在或已过期
        acquired[0] = true;
        return expireAt;
    }
    return existing;                             // 已存在且未过期 = 重复
});
```

生产环境用 Redis 的 `SET key val NX PX ttl` 替换即可，接口不变。这正是**端口-适配器**带来的替换自由。

---

## 四、真实项目场景：一条消息的完整旅程

假设租户 `acme` 的用户在群里 @机器人 发了「帮我查下今天的日程」：

1. **接收**：企微 Webhook 回调到达，入站适配器解密、解析，构造 `InboundMessage`（`channelType=WECOM, chatType=GROUP, mentioned=true`）。
2. **去重**：应用层调用 `idempotencyStore.tryAcquire("acme:msg_10086", Duration.ofMinutes(5))`。首次返回 `true`，放行。
3. **判断**：`inbound.shouldHandle()` -> 群聊且被 @ -> `true`。
4. **发布事件**：发布 `MessageReceivedEvent`，路由/审计模块异步消费。
5. **编排**：Agent 生成回复 Markdown。
6. **回复**：`OutboundMessage.markdownReply(inbound, md)` -> `channelRegistry.dispatch(outbound)` -> `WeComMessageChannel.send()`。
7. 若企微因超时重推了同一条 `msg_10086`，第 2 步 `tryAcquire` 返回 `false`，**直接幂等忽略**，用户不会收到重复回复。

> 本地开发时，第 6 步会命中 [`MockMessageChannel`](src/main/java/com/zero/ai/agentstudy/day14wechatagent/wechatcore/infrastructure/MockMessageChannel.java:20)，直接把回复打印到日志——**零凭证跑通全链路**。

---

## 五、避坑指南（生产级，至少 10 条）

1. **坑：只用 msgId 去重。** 多租户下不同企业 msgId 会碰撞，务必用 `{tenantId}:{msgId}`。参见 `idempotencyKey()`。
2. **坑：幂等键不设 TTL。** Redis 键无限增长最终打爆内存。本章内存实现也做了惰性清理。TTL 建议 5~15 分钟，覆盖网关重试窗口即可。
3. **坑：把消息对象设计成可变。** 管线中任意环节 `setXxx` 都会让问题无法定位。一律不可变 + Builder。
4. **坑：群聊不判断 @ 就处理。** 会导致机器人对群里每句话都回复，瞬间被踢出群。用 `shouldHandle()` 收口。
5. **坑：未知消息类型直接抛异常。** 平台升级即线上事故。用 `of()` 容错回退。
6. **坑：Controller 里写 if-else 分发渠道。** 每加一个渠道就改一次核心代码。用 Registry + Strategy。
7. **坑：入站和出站 traceId 不一致。** 排障时无法串联请求与回复。出站从入站派生 traceId。
8. **坑：渠道投递失败时吞异常。** 用户以为发成功了其实没发。必须抛 `CHANNEL_DELIVERY_FAILED` 并让上层决策重试。
9. **坑：幂等先处理后抢占。** 正确顺序是「先 `tryAcquire` 成功再处理」，否则并发下仍会重复执行。
10. **坑：内存幂等用于多副本部署。** 内存实现不跨实例，多副本必须切 Redis。本章用 `@ConditionalOnMissingBean` 预留了替换位。
11. **坑：tenantId 只在入口校验一次。** 应贯穿全链路，出站 `dispatch` 时用 `supports(tenantId)` 二次校验，防止越权投递。
12. **坑：attributes 直接暴露可变 Map。** 用只读快照，避免适配器透传的原始数据被下游污染。

---

## 六、本章小结

| 维度 | 收获 |
| --- | --- |
| 领域建模 | 不可变值对象 + 领域方法（`shouldHandle`/`idempotencyKey`），把业务规则收敛进模型 |
| 架构模式 | 六边形端口（`MessageChannel`/`IdempotencyStore`）隔离外部脏乱 |
| 设计模式 | Strategy + Registry 消灭渠道 if-else；Builder 构建不可变对象；静态工厂降心智 |
| 可靠性 | 幂等（Idempotent Receiver）+ 容错回退，扛住「至少一次」投递 |
| 多租户 | tenantId 贯穿幂等键、渠道校验、全链路 |

---

## 七、面试题精选

1. **为什么 IM 消息处理必须做幂等？如何设计幂等键？**
   答：IM 网关是 at-least-once 投递，重复推送会导致重复执行。幂等键应带租户维度：`{tenantId}:{msgId}`，用 Redis `SET NX PX` 原子抢占，TTL 覆盖重试窗口。

2. **六边形架构中「端口」和「适配器」分别是什么？本章哪些是端口？**
   答：端口是领域层定义的接口（`MessageChannel`、`IdempotencyStore`），适配器是外部技术的具体实现（`MockMessageChannel`、`InMemoryIdempotencyStore`）。领域只依赖端口，实现可替换。

3. **为什么值对象要设计成不可变？在并发管线里有何优势？**
   答：不可变对象天然线程安全、无需防御性拷贝、状态追溯。管线多环节流转时，能确保 tenantId/traceId 等关键字段不被中途篡改。

4. **新增一个「钉钉」渠道，需要改动哪些代码？体现了什么原则？**
   答：只需新增一个实现 `MessageChannel` 的 `@Component`。注册中心和核心逻辑零改动，体现开闭原则（OCP）与可插拔架构。

---

## 八、扩展阅读

- 《企业集成模式（EIP）》：Message Channel、Message Router、Idempotent Receiver 三个模式是本章直接理论来源。
- 《领域驱动设计（DDD）》Eric Evans：值对象、聚合根、领域事件章节。
- 《实现领域驱动设计（IDDD）》Vaughn Vernon：六边形架构与端口适配器的工程落地。
- Spring 官方文档：`ApplicationEventPublisher` 与 Spring Modulith 事件机制。

---

> 下一章预告：**第三章 会话管理（Session Management）**——如何为每个用户、每个群维护多轮上下文，如何做会话过期、会话隔离与并发安全。