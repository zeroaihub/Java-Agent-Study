# 第三章 会话管理（Session Management）

> 本章配套代码模块：`wechatsession`
> 关键类：`ConversationSession`、`SessionStatus`、`SessionRepository`、`SessionService`、`SessionExpiryScheduler`、`SessionExpiredEvent`

---

## 一、为什么要学会话管理

上一章我们把「一条消息」建模成了不可变的值对象，让它在管线里安全流转。但一个真正的 AI 助手，绝不是「来一条、答一条」的复读机。想象这样一段企业微信里的真实对话：

```
用户：帮我订个会议室
Agent：好的，请问什么时间？
用户：明天下午三点
Agent：需要多大的会议室，大概几个人？
用户：8 个人
Agent：已为您预订「星云厅」，明天 15:00-16:00，可容纳 10 人。
```

这段对话里藏着四个「无状态消息模型」根本回答不了的问题：

1. **多轮上下文**：第二句「明天下午三点」，Agent 凭什么知道这是在回答「什么时间」？它必须记得「我上一轮问了时间，现在正在等用户回答」。
2. **状态**：Agent 在「等用户补充参数」时，和「空闲待命」时，行为完全不同。这是一个**状态机**。
3. **隔离**：同一时刻，成千上万个用户、成百上千个群，各自的对话不能串味。张三的「明天下午三点」不能被安到李四的会议室上。
4. **生命周期**：这段对话如果用户说到一半跑去开会了，半小时没回来，这个「半成品」的上下文要不要一直占着内存？什么时候该判定它「过期」并清理？

**会话（Session）就是承载这一切的容器。** 它是有状态、有身份、有生命周期的对象——与上一章那个「用完即弃」的消息值对象形成鲜明对比。如果说消息是「流过管道的水」，那会话就是「蓄水的池子」。

在企业级场景下，会话管理做不好会直接导致三类线上事故：

- **上下文串味**：并发处理时共享了可变状态，A 用户看到了 B 用户的对话——这是**数据安全事故**，在企业 IM 里等同于泄密。
- **内存泄漏**：会话只创建不清理，QPS 稍高，几小时就 OOM。
- **上下文错乱**：一个用户三个月前的半截对话，今天发消息又被「续」上了，Agent 拿着过期的槽位参数干出莫名其妙的事。

所以本章的目标是：用 DDD 的**聚合根**把会话建模成一个「自我保护」的状态机，并为它配一套「获取-刷新-过期清理」的完整生命周期管理。

---

## 二、会话到底是什么

### 2.1 会话 vs 消息：可变聚合根 vs 不可变值对象

先用一张表厘清两者的本质区别，这是理解本章的钥匙：

| 维度         | 消息（InboundMessage）      | 会话（ConversationSession）          |
| ------------ | --------------------------- | ------------------------------------ |
| DDD 分类     | 值对象（Value Object）      | 聚合根（Aggregate Root）             |
| 可变性       | 不可变（所有字段 final）    | 可变（状态随交互演进）               |
| 身份         | 无身份，靠值相等            | 有身份（sessionKey 唯一标识）        |
| 生命周期     | 瞬时（处理完即弃）          | 长期（跨多轮，直到过期）             |
| 状态变更方式 | 不存在（不可变）            | 只能通过领域方法（touch/resume 等）  |
| 类比         | 流过管道的水                | 蓄水的池子                           |

关键洞察：**会话可变，但不能『随便』变。** 一个裸露 setter 的会话对象是灾难——任何代码都能把 `status` 直接改成任意值，不变式瞬间失守。DDD 聚合根的做法是：**字段私有，状态变更全部走领域方法**，方法内部保证不变式。

### 2.2 会话的身份：三元组

一个会话由什么唯一确定？答案是三元组：

```
(tenantId, channelType, conversationId)
```

- `tenantId`：租户。多租户 SaaS 的第一道隔离墙，A 公司和 B 公司的会话物理上就不可能撞。
- `channelType`：渠道。同一个人可能同时在企业微信和飞书跟 Agent 聊，这是两段独立会话。
- `conversationId`：会话标识。单聊时是用户 ID，群聊时是群 ID。

拼起来就是**会话身份键**：

```
{tenantId}:{channelType}:{conversationId}
例如：acme:wecom:user_1001
```

这个键是会话在仓储里的主键，也是隔离的物理保证——只要键不同，会话就绝不会串。

### 2.3 会话状态机

会话的核心是一个五状态的状态机，定义在 `SessionStatus`：

```
                  touch()
      +--------------------------------+
      v                                |
  [ACTIVE] --idle超时--> [IDLE] --再交互--+
      |                    |
      |                    +--expire超时--> [EXPIRED]
      |
      +--waitInput()--> [WAITING_INPUT] --resume()--> [ACTIVE]
      |
      +--waitApproval()--> [WAITING_APPROVAL] --resume()--> [ACTIVE]
```

五个状态的语义：

| 状态             | 含义                             | 典型场景                       |
| ---------------- | -------------------------------- | ------------------------------ |
| ACTIVE           | 活跃，正常对话中                 | 用户连续发消息                 |
| IDLE             | 空闲，一段时间没交互但未过期     | 用户走开几分钟                 |
| WAITING_INPUT    | 等待用户补充参数                 | 多轮表单收集（订会议室缺时间） |
| WAITING_APPROVAL | 等待人工审批（Human-in-loop）    | 危险操作需主管批准             |
| EXPIRED          | 已过期，待清理                   | 长时间无交互                   |

其中 `WAITING_APPROVAL` 是企业级 Agent 的关键特性——涉及「删库」「发钱」「对外发文」这类高危动作时，Agent 挂起会话、等人点「同意」再继续，这正是后续章节 Human Approval 的落点。

### 2.4 会话在六边形架构中的位置

```
         driving(入站)                                    driven(出站)
             |                                                |
             v                                                v
   +------------------+     +-------------------+     +------------------+
   |  MessageHandler  | --> |  SessionService   | --> | SessionRepository|(port)
   |   (应用编排)      |     |   (应用服务)       |     +------------------+
   +------------------+     +-------------------+              |
                                    |                          v
                                    v                 +------------------+
                            +------------------+       | InMemorySession  |(adapter)
                            |ConversationSession|      |    Repository    |
                            |   (聚合根/领域)    |      +------------------+
                            +------------------+       (Redis实现可无缝替换)
```

- **领域层**：`ConversationSession`（聚合根）+ `SessionStatus`（状态）+ `SessionExpiredEvent`（领域事件）。纯业务，零框架依赖。
- **端口**：`SessionRepository`——领域定义「我需要一个能存取会话的东西」，但不关心它是内存还是 Redis。
- **应用层**：`SessionService`——编排「获取或创建」「刷新」「过期清理」等用例。
- **基础设施层**：`InMemorySessionRepository`（适配器）+ `SessionExpiryScheduler`（定时器）。

这个分层的价值在第三节的代码里会体现得淋漓尽致：**换掉整个存储实现，领域层一行不改。**

---

## 三、怎么用：核心代码逐行解析

### 3.1 聚合根：ConversationSession 如何保护不变式

聚合根最重要的设计原则是：**不允许外部直接改字段**。看它的字段声明：

```java
private final String tenantId;          // 身份，永不变 -> final
private final ChannelType channelType;  // 身份，永不变 -> final
private final String conversationId;    // 身份，永不变 -> final
private final Instant createdAt;        // 创建时刻，永不变 -> final
private Instant lastActiveAt;           // 会变，但只增不减
private SessionStatus status;           // 会变，但只能经领域方法
private long turnCount;                 // 会变，只增
```

三条身份字段是 `final` 的——一旦创建，会话的身份就锁死了。可变的只有 `lastActiveAt`、`status`、`turnCount`，而且它们**没有 public setter**，只能通过领域方法修改。

创建会话用工厂方法而非 `new`，因为「新建一个会话」有固定的初始不变式（状态必为 ACTIVE、轮次为 0、创建即活跃）：

```java
public static ConversationSession create(String tenantId, ChannelType channelType,
                                         String conversationId) {
    Instant now = Instant.now();
    return ConversationSession.builder()
            .tenantId(tenantId)
            .channelType(channelType)
            .conversationId(conversationId)
            .createdAt(now)
            .lastActiveAt(now)          // 创建即活跃
            .status(SessionStatus.ACTIVE)
            .turnCount(0)
            .build();
}
```

### 3.2 领域方法：touch() 记录一次交互

每当用户发来一条消息，会话就要「被触碰」一次——轮次 +1、刷新活跃时间，如果之前是 IDLE 则自动恢复 ACTIVE：

```java
public void touch() {
    this.turnCount++;
    this.lastActiveAt = Instant.now();
    if (this.status == SessionStatus.IDLE) {
        this.status = SessionStatus.ACTIVE;   // 空闲会话被唤醒
    }
}
```

注意这里**只有 IDLE 才恢复**——如果会话正处于 `WAITING_INPUT`（等用户补参数），touch 不应把它踢回 ACTIVE，否则「正在收集参数」的语义就丢了。状态机的每一条边都要想清楚。

### 3.3 过期判定：evaluateExpiry() 的双阈值

会话过期不是「一刀切」，而是两级台阶——先 IDLE，再 EXPIRED：

```java
public boolean evaluateExpiry(Duration idleThreshold, Duration expireThreshold) {
    Duration inactivity = Duration.between(lastActiveAt, Instant.now());
    SessionStatus before = this.status;
    if (inactivity.compareTo(expireThreshold) >= 0) {
        this.status = SessionStatus.EXPIRED;          // 超过大阈值：过期
    } else if (inactivity.compareTo(idleThreshold) >= 0
            && this.status == SessionStatus.ACTIVE) {
        this.status = SessionStatus.IDLE;             // 超过小阈值：空闲
    }
    return before != this.status;   // 返回是否发生了状态变化
}
```

三个设计要点：

1. **先判过期再判空闲**：`if-else` 顺序保证「超过 30 分钟」一步到位 EXPIRED，不会卡在 IDLE。
2. **只有 ACTIVE 才转 IDLE**：`WAITING_INPUT`/`WAITING_APPROVAL` 不应因用户没说话就被降级——它们本来就在「等」。
3. **返回布尔值**：告诉调用方「状态变没变」，让上层决定要不要落库，避免无谓写入。

### 3.4 应用服务：getOrCreate() 编排「获取或创建」

`SessionService.getOrCreate()` 是整个会话生命周期的入口。它处理三种情况：全新会话、命中活跃会话、命中已过期会话：

```java
public ConversationSession getOrCreate(String tenantId, ChannelType channelType,
                                       String conversationId) {
    ConversationSession session = repository
            .find(tenantId, channelType, conversationId).orElse(null);

    if (session == null) {                          // 情况1：全新会话
        session = ConversationSession.create(tenantId, channelType, conversationId);
        repository.save(session);
        return session;
    }

    boolean changed = session.evaluateExpiry(       // 命中旧会话，先推进过期判定
            properties.getIdleThreshold(), properties.getExpireThreshold());
    if (session.getStatus() == SessionStatus.EXPIRED) {   // 情况2：过期后又来消息
        publishExpired(session);                    // 发事件
        repository.delete(session.sessionKey());    // 清理旧会话
        session = ConversationSession.create(tenantId, channelType, conversationId);
        repository.save(session);                   // 重建全新会话
    } else if (changed) {                           // 情况3：状态变了(如转IDLE)
        repository.save(session);                   // 落库
    }
    return session;
}
```

**情况 2 是最容易被忽视的坑**：用户半小时没说话，会话已过期，此刻他又发来一条消息。正确做法是**丢弃旧上下文、重建全新会话**——因为半小时前那段「订会议室订到一半」的上下文早已失去意义，硬续上去只会让 Agent 拿着陈旧的槽位参数犯错。

### 3.5 定时清理：SessionExpiryScheduler

内存实现没有 TTL，得靠定时器主动扫描。关键在于**遍历不可变快照**而非直接遍历 map：

```java
@Scheduled(fixedDelayString = "${wechat.session.sweep-interval-ms:60000}")
public void sweep() {
    if (repository.size() == 0) return;
    sessionService.sweepExpired(repository.snapshot());  // 传入快照，不是活map
}
```

`snapshot()` 返回 `List.copyOf(...)` 的不可变副本，这样扫描期间即使有新会话涌入，也不会触发 `ConcurrentModificationException`——新会话留到下一轮处理即可。

这个组件用 `@ConditionalOnBean(InMemorySessionRepository.class)` 限定：**仅当使用内存实现时才装配**。一旦切到 Redis，Redis 自带 TTL 会自动淘汰过期键，这个定时器就该消失——架构自我调节。

---

## 四、真实项目：一次多轮对话中会话的完整旅程

我们用开篇「订会议室」的例子，把会话状态的每一步演进走一遍。假设租户 `acme`、企业微信单聊、用户 `user_1001`，会话键为 `acme:wecom:user_1001`。

**第 1 轮 —— 用户：「帮我订个会议室」**

1. 消息进入管线，`SessionService.getOrCreate()` 被调用。
2. 仓储查不到 `acme:wecom:user_1001`，走**情况 1**：`create()` 新建会话，状态 ACTIVE，turnCount=0，落库。
3. 应用层调用 `touch()`：turnCount=1，刷新活跃时间。
4. 路由识别出「订会议室」命令，但缺「时间」参数 -> 调用 `session.waitInput()`，状态转 `WAITING_INPUT`，落库。
5. Agent 回复「请问什么时间？」

**第 2 轮 —— 用户：「明天下午三点」（20 秒后）**

1. `getOrCreate()` 命中会话，`evaluateExpiry()` 判定：20 秒 < 5 分钟，无变化。
2. `touch()`：turnCount=2。因为当前是 `WAITING_INPUT` 而非 IDLE，**touch 不改状态**——「等待输入」的语义保住了。
3. 命令系统从槽位读出「已知：订会议室」，把「时间=明天15:00」写入槽位 `session.putSlot("time", ...)`，仍缺「人数」-> 保持 `WAITING_INPUT`。
4. Agent 回复「需要多大的会议室？」

**第 3 轮 —— 用户：「8 个人」**

1. 命中、touch（turnCount=3），槽位补齐「人数=8」。
2. 参数收集完成 -> `session.resume()`，状态回 ACTIVE；执行订会议室；`session.clearSlots()` 清空临时槽位。
3. Agent 回复「已为您预订星云厅…」

**用户走开了（半小时无交互）**

1. 定时器每 60 秒 `sweep()` 一次。
2. 第一次达到 5 分钟：`evaluateExpiry()` 把 ACTIVE 转 IDLE，落库。
3. 达到 30 分钟：转 EXPIRED，发布 `SessionExpiredEvent`，从仓储删除。会话彻底出清，内存回收。

**用户 40 分钟后又发消息**

1. `getOrCreate()` 查不到（已被清理）-> 走**情况 1**，全新会话。上一段「订会议室」的上下文不会污染这次对话。

这条旅程完整展示了状态机的每一次跃迁，以及**槽位（slot）**作为「短期临时上下文」如何配合状态收集多轮参数——它区别于第六章要讲的长期 Memory。

---

## 五、避坑指南（12 条生产血泪）

1. **绝不给聚合根加裸 setter。** 一旦有了 `setStatus()`，任何角落的代码都能绕过状态机把会话改成非法状态。状态变更只能走 `touch/waitInput/waitApproval/resume/evaluateExpiry`。

2. **身份字段必须 final。** `tenantId/channelType/conversationId` 一旦可变，会话身份就会漂移，仓储里的键和对象自身对不上，引发「幽灵会话」。

3. **会话键格式只写一处。** 我们把 `sessionKey()` 的拼接逻辑收敛到 `ConversationSession` 的静态方法，仓储复用它。若仓储自己拼一遍键，格式一旦漂移（比如分隔符从 `:` 变 `#`），查找永远 miss。

4. **过期判定顺序：先 EXPIRED 再 IDLE。** 写成先判 IDLE 会导致超过大阈值的会话卡在 IDLE 永不过期，内存缓慢泄漏。

5. **只有 ACTIVE 才降级为 IDLE。** `WAITING_INPUT`/`WAITING_APPROVAL` 不能因用户沉默被降级——用户本来就在「等 Agent」或「Agent 在等用户」，这不是空闲。

6. **过期后来消息要重建，不要复用。** 复用一段早已失效的上下文，会让 Agent 拿着几十分钟前的槽位参数干活，行为诡异且难排查。

7. **定时清理必须遍历快照，不遍历活 map。** 直接 `for (session : store.values())` 一边遍历一边 `delete`，`ConcurrentModificationException` 分分钟炸给你看。用 `List.copyOf()` 的不可变快照。

8. **内存实现要标注『会随重启丢失』。** 别让接手的人误以为内存仓储能扛生产。JavaDoc 明确写清适用边界，并用 `@ConditionalOnMissingBean` 为 Redis 实现预留替换位。

9. **阈值要外部化且带默认值。** `idleThreshold`/`expireThreshold` 用 `@ConfigurationProperties` 承载并给默认值（5 分钟 / 30 分钟），保证零配置可跑，又允许生产覆写。硬编码 `30 * 60 * 1000` 是维护噩梦。

10. **过期先发事件，再删数据。** `publishExpired()` 必须在 `delete()` 之前调用——否则会话已被删，事件里想带的 `turnCount` 等信息就取不到了。

11. **`@Scheduled` 别忘了 `@EnableScheduling`。** 定时器不生效，十有八九是启动类漏了这个注解。这是新手最常见的「代码没错但不跑」。

12. **多租户隔离要贯穿到键，而非只在查询时过滤。** 把 `tenantId` 拼进会话键，让不同租户的会话在存储层就物理隔离；靠「查出来再 if 判断租户」既慢又容易漏判。

---

## 六、小结

| 概念               | 一句话记忆                                             |
| ------------------ | ------------------------------------------------------ |
| 会话是聚合根       | 有状态、有身份、有生命周期，与消息值对象相对            |
| 身份三元组         | (tenantId, channelType, conversationId) 唯一确定会话   |
| 状态机             | ACTIVE/IDLE/WAITING_INPUT/WAITING_APPROVAL/EXPIRED     |
| 领域方法保护不变式 | 字段私有，变更只走 touch/resume/evaluateExpiry 等      |
| 双阈值过期         | 先 IDLE（软），后 EXPIRED（硬），再清理                |
| 槽位 slot          | 短期临时上下文，区别于长期 Memory                      |
| 端口适配器         | Repository 端口解耦内存/Redis/JPA，领域层不动          |
| 定时清理           | 内存实现靠 Scheduler 扫快照；Redis 靠自带 TTL          |

---

## 七、面试题

**Q1：为什么会话建模成可变聚合根，而消息建模成不可变值对象？**
A：消息是「流转对象」，一旦产生其内容就是事实，不该被改，不可变让它在并发管线里天然线程安全；会话是「状态载体」，本质就是随交互演进的状态机，必须可变。但会话的可变性被约束在领域方法内，字段私有、无裸 setter，从而在「可变」与「可控」之间取得平衡。

**Q2：如何保证高并发下不同用户的会话不串味？**
A：三层保证——(1) 会话身份键含 `tenantId`，物理隔离到存储层；(2) 仓储用 `ConcurrentHashMap` 以键索引，键不同则对象不同；(3) 每次处理从仓储按键取出独立会话对象，不共享可变状态。杜绝「用一个全局可变对象服务所有用户」。

**Q3：会话过期为什么要分 IDLE 和 EXPIRED 两级？**
A：IDLE 是「软过期」——用户可能只是走开一会儿，会话还留着，再来消息立即唤醒，上下文不丢；EXPIRED 是「硬过期」——超过更长阈值判定对话真正结束，清理释放内存。两级台阶兼顾了「短暂离开的体验连续性」与「长期无用的资源回收」。

**Q4：内存会话仓储切换到 Redis，需要改哪些代码？**
A：只需新增一个实现 `SessionRepository` 接口、名为 `redisSessionRepository` 的 Bean。因为 `InMemorySessionRepository` 标了 `@ConditionalOnMissingBean(name="redisSessionRepository")`，Redis Bean 一出现它就自动退场；`SessionExpiryScheduler` 标了 `@ConditionalOnBean(InMemorySessionRepository.class)`，内存实现退场后它也随之消失（Redis 用自带 TTL）。**领域层、应用层一行不改**——这正是六边形架构端口适配器的威力。

---

## 八、扩展阅读

- 《领域驱动设计》（Eric Evans）第 5、6 章：实体、值对象、聚合的建模原则。
- 《实现领域驱动设计》（Vaughn Vernon）第 10 章：聚合设计四原则（尤其「一个事务只修改一个聚合」）。
- Martin Fowler，《Patterns of Enterprise Application Architecture》：Repository 模式、Identity Field。
- Redis 官方文档：`EXPIRE`/`TTL` 与会话存储的天然契合。
- 状态模式（State Pattern）：GoF 设计模式中处理「对象行为随状态改变」的经典方案，可作会话状态机的进阶重构方向。

> 下一章预告：会话解决了「记住上下文」，但一条消息进来后，Agent 怎么决定「这该谁处理」？第四章《消息路由》将引入路由策略与责任链，把命令、闲聊、@机器人分发到正确的处理器。