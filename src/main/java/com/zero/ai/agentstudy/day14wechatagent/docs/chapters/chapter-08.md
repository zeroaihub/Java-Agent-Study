# 第八章 通知中心与 Webhook：消息可靠送达的最后一公里

> 本章配套模块：`wechatnotify`
> 关键词：通知中心、多渠道策略路由、幂等去重、失败重试、死信队列、Webhook 验签、状态机

---

## 一、为什么学：Agent 干完活，怎么把结果「稳稳」送到人手里

第七章我们让 Agent 学会了规划与执行。可流水线跑完之后，还有一件事没解决——**把结果送出去**。

回到那句贯穿全课程的话：

> 「帮我整理今天所有会议内容，生成会议纪要，**发送给项目组**……」

「发送给项目组」这五个字，看似只是一次消息推送，落到生产环境却是一地坑：

1. **渠道不止一个**：项目组要企业微信 Markdown 卡片，老板要邮件，值班要短信，外部系统要 Webhook 回调。一份纪要，四种渠道。
2. **发送会失败**：企业微信 API 限流、access_token 过期、网络抖动。失败了要不要重试？重试几次？
3. **重试会重复**：上一次其实发成功了，只是回执超时。你以为失败重发，用户收到两条一模一样的纪要——**幂等**问题。
4. **重试也救不回来**：内容超了 4096 字节上限、邮箱地址非法。这类错误重试一万次也没用，应当**快速失败**并归档。
5. **彻底失败之后呢**：重试耗尽了还是发不出去，消息不能凭空消失——要进**死信队列**，等人工介入重投。
6. **别人也会回调你**：企业微信的消息回执、审批结果会通过 **Webhook** 打回来。怎么确认这是企业微信发的、不是黑客伪造的？——**验签**。

一个只会 `System.out.println("已发送")` 的通知逻辑，在生产环境是灾难。我们需要一个**通知中心（Notification Center）**：企业级「消息可靠送达」的统一出口。

> 一句话总结：**通知中心把「多渠道 + 幂等 + 重试 + 死信」这些脏活累活收敛到一处，让业务代码只需说「把这条通知发出去」，剩下的可靠性交给基础设施。**

本章配套模块 `wechatnotify`，正是第七章 Workflow 里 `NotifySendExecutor` 背后真正的送达引擎，也是第十章终极场景「发送给项目组」这一步的落地实现。

---

## 二、是什么：通知中心的五个核心构件

### 2.1 通知聚合根 Notification —— 一条通知的完整生命

`Notification` 是本模块的聚合根（可变实体），承载一条通知从「待发」到「已送达」或「进死信」的全过程状态。核心字段：

| 字段 | 含义 |
| --- | --- |
| `id` / `tenantId` | 通知唯一标识 / 租户隔离 |
| `channel` | 投递渠道（枚举） |
| `target` | 接收方（群 ID / 邮箱 / 手机号 / URL） |
| `title` / `content` / `markdown` | 标题 / 正文 / 是否 Markdown |
| `dedupeKey` | 幂等键（可空） |
| `maxRetries` | 最大重试次数 |
| `status` | 投递状态（状态机核心） |
| `attempts` | 已尝试次数 |
| `lastError` | 最近一次失败原因 |
| `sentAt` | 送达时间 |

### 2.2 状态机 NotifyStatus —— 通知的四种命运

一条通知的状态，本质是一个小状态机：

```
                 markSending
    +---------+  attempts++   +----------+
    | PENDING | ------------> | (发送中)  |
    +---------+               +----------+
       ^  |                      |     |
       |  | markFailed           | ok  | fail 且不可重试 / 渠道缺失
       |  | 且可重试             v     v
       |  +----------------> +------+  +------+
       |                     | SENT |  | DEAD |
       |  markFailed         +------+  +------+
       |  且可重试(回退)         终态      终态(死信)
```

- **PENDING**：待投递（初始态，或失败后仍可重试时回退到此）。
- **SENT**：已送达（终态）。
- **FAILED**：本次失败（过渡态，`markFailed` 内部据 `canRetry()` 立即决定回 PENDING 还是进 DEAD）。
- **DEAD**：死信（终态）——重试耗尽、渠道缺失、或不可重试错误。

判定方法：

- `isTerminal()`：`SENT` 或 `DEAD`（终态，投递循环见此即停）。
- `isRetryable()`：`PENDING` 或 `FAILED`（还有救）。

### 2.3 多渠道策略路由 NotifySender —— 开闭原则的教科书

发送端口 `NotifySender` 只有两个方法：

```java
boolean supports(NotifyChannel channel);   // 我能发这个渠道吗？
SendResult send(Notification notification); // 发！返回结果（不抛异常）
```

每个渠道一个实现类（`WechatWorkNotifySender` / `EmailNotifySender` / `WebhookNotifySender`），各自 `supports` 认领自己的渠道。通知中心拿到一条通知，遍历所有 sender 找到第一个 `supports` 为 true 的来投递。

> **新增一个渠道 = 新增一个 sender 实现类，通知中心一行不改**。这就是开闭原则（对扩展开放，对修改关闭）。`NotifyChannel` 里预留了 `SMS` 枚举但暂未实现 sender，正是留给你练手的扩展点——补一个 `SmsNotifySender` 即可。

### 2.4 幂等去重 dedupeKey —— 防止「发两遍」

带 `dedupeKey` 的通知，仓储维护一个 `dedupeKey -> id` 的索引。投递前先查：若已存在且状态为终态（已 SENT），直接返回旧结果，**跳过重复投递**。这样即使上游因为重试、消息重放触发了两次 `send`，用户也只会收到一条。

### 2.5 死信队列 + 人工重投 —— 消息永不丢失

重试耗尽仍失败的通知进入 `DEAD`。通知中心提供：

- `deadLetters(tenantId)`：捞出某租户所有死信，供管理后台展示。
- `redeliver(id)`：人工确认后，基于死信内容构造一条**全新通知**重新投递（而非复用旧实体，保证 attempts 从零计数、状态干净）。

### 2.6 Webhook 验签 —— 别人回调你时，怎么确认是「真的他」

企业微信等系统会通过 Webhook 把事件打回来。`WebhookInboundService` 用三道关口守门：

1. **验签**：用共享密钥对 payload 算 HMAC-SHA256，与请求头签名做**常量时间比较**（防时序攻击），不符即拒。
2. **幂等**：同一 `eventId` 只处理一次，防重复回调导致重复副作用。
3. **异步处理**：验签幂等通过后，业务处理应投递到队列异步化（教学版同步演示）。

---

## 三、怎么用：逐段拆解通知中心的核心代码

### 3.1 Notification 聚合根：把状态迁移封装在实体里

聚合根的黄金法则是**状态迁移只能通过实体方法完成**，外部不能直接 set 状态字段。看 `markFailed`——它是整个重试/死信机制的枢纽：

```java
/** 标记本次发送失败：可重试则回 PENDING，否则进 DEAD 死信。 */
public void markFailed(String error) {
    this.lastError = error;
    if (canRetry()) {
        this.status = NotifyStatus.PENDING;   // 还有重试机会，回到待发
    } else {
        this.status = NotifyStatus.DEAD;      // 重试耗尽，进死信
    }
}

/** 是否还能重试：已尝试次数未超过上限。 */
public boolean canRetry() {
    return attempts <= maxRetries;
}
```

配套的 `markSending` 在每次投递前把 `attempts++`：

```java
public void markSending() {
    this.attempts++;
}
```

于是「总共尝试 `maxRetries + 1` 次」这个语义，被 `attempts++` 与 `canRetry()` 两个方法自然表达出来——这正是把业务规则收敛进领域实体的价值。

工厂方法让创建通知更语义化：

```java
// 企业微信 Markdown 通知
Notification.wechatMarkdown(tenantId, groupId, title, markdownContent);
// 带幂等键的通知
notification.withDedupe("meeting-summary-20260804");
```

### 3.2 SendResult：用返回值而非异常表达失败

发送结果是一个不可变值对象（record），三态由 `success` + `messageId` + `error` 表达：

```java
public record SendResult(boolean success, String messageId, String error) {
    public SendResult {
        // 紧凑构造器：成功则清空 error，失败则兜底一句错误说明
        if (success) {
            error = null;
        } else if (error == null || error.isBlank()) {
            error = "未知发送失败";
        }
    }
    public static SendResult ok(String messageId) { return new SendResult(true, messageId, null); }
    public static SendResult fail(String error)   { return new SendResult(false, null, error); }
}
```

> **为什么不用抛异常表达失败？** 因为「发送失败」是**预期内的正常业务分支**（限流、超限太常见了），不是程序错误。用返回值让调用方必须显式处理成功/失败两条路径，代码意图更清晰；异常则留给「真正意外」（如 sender 内部空指针），由通知中心 try-catch 兜底转成 `fail`。

### 3.3 NotificationCenter.send：整个模块的心脏

投递主流程分三步——去重、路由、重试循环：

```java
public Notification send(Notification notification) {
    // 1) 幂等去重：终态的重复通知直接返回旧结果
    if (notification.dedupeKey() != null) {
        Optional<Notification> existing = repository.findByDedupeKey(notification.dedupeKey());
        if (existing.isPresent() && existing.get().status().isTerminal()) {
            return existing.get();
        }
    }
    repository.save(notification);

    // 2) 渠道路由：找不到 sender 直接进死信
    Optional<NotifySender> sender = findSender(notification.channel());
    if (sender.isEmpty()) {
        notification.markDead("无可用的发送器: " + notification.channel());
        repository.save(notification);
        return notification;
    }

    // 3) 投递重试循环
    while (notification.canRetry()) {
        notification.markSending();
        SendResult result;
        try {
       result = sender.get().send(notification);
        } catch (Exception ex) {
            result = SendResult.fail("发送器抛出异常: " + ex.getMessage());
        }
        if (result.success()) {
            notification.markSent(result.messageId());
            repository.save(notification);
            return notification;               // 成功，出循环
        }
        notification.markFailed(result.error());
        repository.save(notification);
        if (notification.status().isTerminal()) {
            break;                             // 已 DEAD，停止重试
        }
    }
    return notification;
}
```

三个设计细节值得体会：

- **去重发生在最前**：连仓储都还没存，先查幂等键，避免无谓的投递。
- **渠道缺失即死信**：`SMS` 枚举存在但没有 `SmsNotifySender`，此路径会命中 `markDead`——这是**故意保留的可观测失败**，而非静默丢弃。
- **sender 抛异常也不崩**：try-catch 把意外异常转成 `fail`，让重试机制照常运转，绝不让一个 sender 的 bug 拖垮整个通知中心。

### 3.4 WechatWorkNotifySender：策略实现 + 不可重试校验

主渠道实现认领 `WECHAT_WORK`，并在发送前做**内容长度校验**——这是「不可重试错误」的典型：

```java
private static final int WECHAT_MARKDOWN_LIMIT = 4096; // 企业微信 Markdown 字节上限

@Override
public boolean supports(NotifyChannel channel) {
    return channel == NotifyChannel.WECHAT_WORK;
}

@Override
public SendResult send(Notification notification) {
    byte[] bytes = notification.content().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > WECHAT_MARKDOWN_LIMIT) {
        // 超限：重试也没用，直接 fail —— 通知会快速走向死信
        return SendResult.fail("Markdown 内容超过 4096 字节上限，实际 " + bytes.length);
    }
    // TODO 生产：调用企业微信 API（先用 corpId/corpSecret 换 access_token，再 POST 消息）
    String messageId = "wxwork-" + UUID.randomUUID();
    log.info("[企业微信] 已推送到 {}，messageId={}", notification.target(), messageId);
    return SendResult.ok(messageId);
}
```

> **关键判断力**：什么错误该重试，什么不该？「网络超时、限流」是**瞬时错误**，重试有意义；「内容超限、邮箱非法、URL 非法」是**永久错误**，重试纯属浪费——直接 `fail` 让它尽快进死信，等人工修数据。`EmailNotifySender`（非法邮箱）、`WebhookNotifySender`（非法 URL）同理。

### 3.5 死信重投：基于死信内容构造全新通知

```java
public Notification redeliver(String notificationId) {
    Notification dead = repository.findById(notificationId)
        .orElseThrow(() -> new IllegalArgumentException("通知不存在: " + notificationId));
    // 基于死信内容构造一条全新通知：attempts 归零、状态干净
    Notification fresh = Notification.wechatMarkdown(
        dead.tenantId(), dead.target(), dead.title(), dead.content());
    return send(fresh);
}
```

> **为什么不直接复活旧实体？** 旧实体的 `attempts` 已耗尽、`lastError` 有历史污染。构造全新通知让重投拥有干净的重试预算和状态，语义上也更清晰——「这是一次新的投递尝试」，而非「修改一条已经失败的记录」。

---

## 四、真实项目：会议纪要如何「稳稳」送到项目组

现在把第七章和本章串起来。第七章 Workflow 的最后一步是 `NotifySendExecutor`，在那一章它还是个 Mock（只打日志）。本章我们让它调用真正的通知中心。

### 4.1 从 Mock 升级为真实送达

第七章的执行器伪代码：

```java
// 第七章：Mock 版本
class NotifySendExecutor implements StepExecutor {
    public StepResult execute(WorkflowStep step, ExecutionContext ctx) {
        log.info("[Mock] 假装发送给项目组: {}", ctx.get("summaryUrl"));
        return StepResult.success();
    }
}
```

本章升级后：

```java
// 第八章：接入通知中心
class NotifySendExecutor implements StepExecutor {
    private final NotificationCenter notificationCenter;   // 注入通知中心

    public StepResult execute(WorkflowStep step, ExecutionContext ctx) {
        String groupId  = (String) ctx.get("projectGroupId");
        String title    = "今日会议纪要";
        String markdown = (String) ctx.get("summaryMarkdown");

        Notification notification = Notification
            .wechatMarkdown(ctx.tenantId(), groupId, title, markdown)
            .withDedupe("meeting-summary-" + ctx.get("meetingDate")); // 幂等键防重发

        Notification sent = notificationCenter.send(notification);

        return sent.status() == NotifyStatus.SENT
            ? StepResult.success()
            : StepResult.failure("通知投递未成功: " + sent.lastError());
    }
}
```

### 4.2 一条纪要的完整送达旅程

```
Workflow 最后一步 NotifySendExecutor
        |
        v
NotificationCenter.send(notification)
        |
        | (1) 幂等去重：查 dedupeKey "meeting-summary-20260804"
        |     -- 首次投递，无记录，继续
        v
        | (2) 路由：channel=WECHAT_WORK -> WechatWorkNotifySender
        v
        | (3) 投递循环 (maxRetries=2, 共 3 次机会)
        |
        |   attempt 1: markSending(attempts=1) -> send()
        |              企业微信限流 -> fail("rate limited")
        |              markFailed -> canRetry(1<=2)=true -> 回 PENDING
        |
        |   attempt 2: markSending(attempts=2) -> send()
        |              成功 -> ok("wxwork-abc123")
        |              markSent -> status=SENT -> 返回
        v
   项目组收到 Markdown 卡片，纪要送达 ✓
```

### 4.3 异常支线：如果一直失败会怎样

```
   attempt 1: fail -> PENDING (attempts=1)
   attempt 2: fail -> PENDING (attempts=2)
   attempt 3: fail -> markFailed -> canRetry(3<=2)=false -> DEAD 死信
        |
        v
   进入死信队列，管理后台 deadLetters(tenantId) 可见
        |
        v
   运维排查（如企业微信配置错误）修复后，点击「重投」
        |
        v
   redeliver(id) -> 构造全新通知 -> 重新走 send 流程 -> 送达 ✓
```

这条链路，正是第十章终极场景「发送给项目组」这一步的完整落地——**Agent 干完活，结果稳稳送达，失败也不丢**。

---

## 五、避坑指南：通知中心的 12 个真实教训

1. **不做幂等，重试即重复推送**。上一次其实发成功了只是回执超时，你重发一次，用户收到两条一模一样的纪要。务必给关键通知带 `dedupeKey`，投递前查终态。

2. **企业微信有频率限制**。同一应用每分钟消息数、每用户每日消息数都有上限，触发即返回 45009 等错误码。要做**客户端限流**（令牌桶）+ 遇到限流码走重试退避，别硬刚。

3. **access_token 会过期且有并发刷新问题**。企业微信 token 有效期 7200 秒，且**新 token 签发会使旧 token 失效**。多实例同时刷新会互相踢下线——要用分布式锁 + 缓存（Redis）统一管理 token 刷新。

4. **Markdown 有 4096 字节上限**。会议纪要动辄超长，直接发会被拒。本章 `WechatWorkNotifySender` 做了超限校验并快速 fail，生产还应做**内容截断 + 附件/链接兜底**（长内容传文件，消息里只放摘要 + 链接）。

5. **死信不能只进不出**。进了 DEAD 却没人管，等于消息丢了。必须有**死信监控告警** + 管理后台重投入口 + 定期巡检。本章 `deadLetters` / `redeliver` 就是为此准备。

6. **Webhook 不验签会被伪造**。任何人只要知道你的回调 URL，就能伪造企业微信事件打进来。务必 HMAC 验签 + 常量时间比较（防时序攻击），密钥从密钥管理服务读取，别硬编码。

7. **同步投递会阻塞主流程**。Workflow 里同步调 `send`，如果企业微信 API 慢，整条流水线卡住。生产应把投递**异步化**（投递到消息队列，由消费者发送），`send` 只负责入队。本章教学版为清晰起见用了同步。

8. **重试没有退避会加剧雪崩**。失败立刻重试、连发三次，只会让本就限流/过载的下游更崩。要用**指数退避 + 抖动**（1s、2s、4s + 随机）。本章循环未加 sleep，生产务必补上。

9. **不区分可重试/不可重试错误，白白浪费重试预算**。邮箱非法、内容超限重试一万次也没用。要识别**永久错误**直接 fail 进死信，把重试预算留给瞬时错误（超时、限流）。

10. **多渠道要有降级策略**。企业微信发不出去，重要通知应降级到短信/邮件。本章按单渠道投递，生产可在死信后触发**跨渠道降级**（如「企微失败则转发邮件」）。

11. **内存仓储重启即丢死信**。`InMemoryNotificationRepository` 一重启，所有待发和死信全没了。生产必须用**持久化仓储**（PostgreSQL），死信是要留档追责的数据，绝不能只在内存。

12. **tenantId 必须贯穿全链路**。`deadLetters(tenantId)` 按租户过滤，若忘记传 tenantId 或用错，A 租户会看到 B 租户的死信——**多租户越权**。所有查询、投递都要带 tenantId 做隔离。

---

## 六、面试题：通知中心必答的 4 道

### Q1：如何保证消息「至少一次」且「不重复」送达？

「至少一次」靠**重试 + 死信**：失败自动重试，重试耗尽进死信不丢失，人工可重投。「不重复」靠**幂等去重**：为通知分配业务幂等键（`dedupeKey`），投递前查询，若已处于终态（SENT）则跳过。二者结合，实现工程上可接受的「恰好一次」效果——注意分布式系统里严格的 exactly-once 极难做到，实践都是「at-least-once + 幂等」逼近它。

### Q2：什么错误该重试，什么不该？如何设计？

按错误性质分两类：**瞬时错误**（网络超时、限流、下游临时不可用）——重试有效，应重试 + 指数退避；**永久错误**（内容超限、参数非法、认证失败、目标不存在）——重试无效，应立即失败进死信。本章在各 sender 里对内容长度、邮箱、URL 做前置校验并直接 `fail`，就是永久错误快速失败的体现。设计上可给错误分类（RETRYABLE / PERMANENT），由通知中心据类别决定是否回 PENDING。

### Q3：Webhook 回调如何防伪造和防重复？

**防伪造靠验签**：调用方与接收方约定共享密钥，对 payload 计算 HMAC-SHA256 签名放在请求头，接收方用同样密钥重算并比对，且用**常量时间比较**防时序攻击。**防重复靠幂等**：每个事件带唯一 `eventId`，接收方维护已处理集合（生产用 Redis 带 TTL），重复 eventId 直接跳过。本章 `WebhookInboundService` 三道关口（验签 → 幂等 → 异步处理）正是标准实践。

### Q4：为什么用返回值（SendResult）而非异常来表达发送失败？

因为「发送失败」是**预期内的正常业务分支**（限流、超限极其常见），不是程序缺陷。用返回值强制调用方显式处理成功/失败两条路径，代码意图清晰，也避免异常的性能开销与控制流跳跃。异常应留给**真正意外**（如 sender 内部 NPE），本章通知中心用 try-catch 把这类意外兜底转成 `fail`，保证重试机制不被意外击穿。这是「用异常处理异常，用返回值处理业务分支」的经典原则。

---

## 七、小结

| 构件 | 职责 | 关键设计 |
| --- | --- | --- |
| `Notification` | 通知聚合根 | 状态迁移封装在实体方法内 |
| `NotifyStatus` | 状态机 | PENDING/SENT/FAILED/DEAD，终态判定 |
| `NotifySender` | 发送端口 | 策略模式，supports 认领渠道，开闭原则 |
| `SendResult` | 结果值对象 | 返回值表达三态，不用异常 |
| `NotificationCenter` | 通知中心 | 去重 → 路由 → 重试循环 → 死信 |
| `NotificationRepository` | 仓储端口 | save/findById/findByDedupeKey/findDeadLetters |
| `WebhookInboundService` | 回调接收 | 验签 + 幂等 + 异步处理 |

本章我们把「发送给项目组」这个看似简单的动作，拆成了**多渠道路由、幂等去重、失败重试、死信兜底、Webhook 验签**五个企业级子问题，并用六边形架构逐一落地。至此，第七章的「规划 + 执行」有了可靠的「送达」出口，第十章终极场景的最后一块拼图就位。

---

## 八、扩展阅读

- **企业微信服务端 API**：应用消息推送、access_token 管理、消息类型（text/markdown/textcard/图文）、回调配置与验签（`WXBizMsgCrypt`）。
- **消息可靠投递模式**：Outbox Pattern（本地消息表）、事务消息（RocketMQ）、at-least-once + 幂等消费。
- **死信队列（DLQ）**：RabbitMQ / Kafka 的死信机制、死信重投与告警。
- **重试与退避**：Spring Retry、Resilience4j（Retry / RateLimiter / CircuitBreaker）、指数退避 + 抖动算法。
- **Webhook 安全**：HMAC 签名验证、时间戳防重放、常量时间比较、Stripe/GitHub Webhook 设计规范。

---

## 九、下一章预告

通知中心让消息稳稳送达，但整个平台还缺一个**对外的门面**和一个**给运营看的后台**。

**第九章《API 网关与管理后台》**（配套模块 `wechatapi` / `wechatadmin`）将带你：

- 用 REST API 把前八章的能力（消息、会话、Workflow、通知）暴露成统一接口，做好**认证、限流、参数校验、统一响应**。
- 搭建管理后台视角：会话监控、Workflow 任务看板、**死信队列管理与重投**（正是本章 `deadLetters` / `redeliver` 的 UI 出口）、租户配置。
- 把六边形架构的「入站适配器」（Controller）补齐，让 Agent 平台真正可被外部系统调用。

至此，一个可对外服务、可运营管理的企业级 AI WeChat Agent 平台雏形完整成型，只待第十章把所有能力串成「会议纪要全链路」的终极场景。