# Chapter 06 · 事件驱动（Event Driven）

> 本章目标：让 Agent 从"我主动轮询"进化到"有变化才响应"。讲透**发布/订阅**模式如何解耦系统、支撑长任务的响应式编排，并串起 Chapter 05 的任务队列。给出 `AgentEvent`、`EventListener`、`EventBus` 三个完整可运行代码。

---

## 第一部分 · 为什么学

### 6.1 轮询驱动的天花板

Chapter 05 的调度器靠心跳"每 500ms 看一眼队列"——这是**轮询（Polling）**。轮询简单可靠，但有天花板：

- **延迟**：事情发生到被处理，最坏要等一个轮询周期。
- **空转**：大部分心跳都扑空（队列没到期任务），浪费 CPU。
- **耦合**：想在"会话失败时"顺带告警 + 记审计 + 通知上游，若都写进失败处理逻辑里，这段代码会越来越臃肿，改一处动全身。

长生命周期 Agent 里"一件事发生、多方需要响应"的场景太多了：一个 Step 完成，可能要打点、要发进度通知、要检查是否触发下一步……把这些反应硬编码在一起是维护灾难。

### 6.2 事件驱动的答案

**事件驱动**换一种思维：不再是"我去问有没有事发生"，而是"事发生了，主动喊一嗓子，谁关心谁来听"。

- **事件（Event）**：已经发生的事实，过去式、不可变（`StepCompleted`、`SessionFailed`、`ApprovalReceived`）。
- **发布者（Publisher）**：事情发生时 `publish(event)`，然后就不管了。
- **订阅者（Listener）**：声明"我关心某类事件"，事件来了自动被回调。
- **事件总线（EventBus）**：中间人，负责把事件路由给所有关心它的订阅者。

**核心价值是解耦**：发布者不知道谁在听，订阅者不知道谁在发。新增一种反应？加个 Listener 就行，发布方一行不改（开闭原则）。

### 6.3 类比：小区广播

物业在广播站喊"3 号楼要停水了"（发布事件）。谁装了喇叭谁能听到（订阅）：住户去接水、保洁去准备、餐厅去调整——广播员根本不认识这些人。这就是发布/订阅：**一次发布，多方响应，彼此不认识。**

---

## 第二部分 · 是什么

### 6.4 发布/订阅的数据流

```
   发布者(任意模块)                                    订阅者(任意数量)
   ┌──────────────┐   publish(event)   ┌──────────┐   ┌─────────────────┐
   │ Runtime      │ ─────────────────► │          │──►│ AuditListener(*) │
   │ Dispatcher   │                    │ EventBus │   ├─────────────────┤
   │ Scheduler    │                    │  (路由)   │──►│ NotifyListener   │
   │ 业务代码      │                    │          │   ├─────────────────┤
   └──────────────┘                    └──────────┘──►│ ResumeListener   │
                                        按 type 路由    └─────────────────┘
                                        + 通配"*"桶
        发布者不持有订阅者引用            单个 Listener 抛异常被隔离，不影响其它
```

### 6.5 事件模型 AgentEvent

用不可变 `record` 承载，一旦发布不可篡改——这是**事件溯源（Event Sourcing）**的基石（Chapter 04 提到的 Temporal/Cadence 正是靠不可变事件流重放来恢复状态）：

```java
public record AgentEvent(
        String eventId,      // 唯一标识：去重、幂等、追踪
        String type,         // 订阅路由键
        String sessionId,    // 关联会话（可空=全局事件）
        Object payload,      // 业务数据
        Instant occurredAt   // 发生时间（过去式）
) {
    public AgentEvent {
        if (type == null || type.isBlank())
            throw new IllegalArgumentException("事件 type 不能为空");
        if (eventId == null || eventId.isBlank()) eventId = UUID.randomUUID().toString();
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public static AgentEvent of(String type, String sessionId, Object payload) { ... }
    public static AgentEvent global(String type, Object payload) { ... }  // 无会话全局事件
}
```

紧凑构造器里做了三件事：**校验 type 非空、自动补 eventId、自动补时间**——让调用方最省心。`eventId` 是幂等的关键（同一事件被重放/重投时可去重，Chapter 08 详述）。

### 6.6 订阅者 EventListener

```java
public interface EventListener {
    String interestedType();          // 关心的事件类型；返回 "*" 订阅全部
    void onEvent(AgentEvent event);   // 事件回调
    String WILDCARD = "*";
}
```

`interestedType()` 返回订阅路由键，`"*"` 通配表示订阅所有事件——审计、全量日志这类"什么都想听"的监听器就用它。

### 6.7 事件总线 EventBus

中枢。三个设计要点全在这：

**① 零配置自动订阅**——构造时用 Spring 的 `List<EventListener>` 注入所有监听器 Bean，按 `interestedType` 自动归类：

```java
@Component
public class EventBus {
    private final Map<String, List<EventListener>> registry = new ConcurrentHashMap<>();

    public EventBus(List<EventListener> listeners) {   // Spring 注入全部监听器
        for (EventListener listener : listeners) {
            subscribe(listener);
        }
    }
}
```

**② 精确 + 通配双路由**——发布时既通知精确类型订阅者，也通知 `"*"` 通配订阅者：

```java
public void publish(AgentEvent event) {
    if (event == null) return;
    dispatch(registry.get(event.type()), event);            // 精确类型
    dispatch(registry.get(EventListener.WILDCARD), event);  // 通配
}
```

**③ 异常隔离**——`dispatch` 逐个 `try-catch`，某个监听器抛异常绝不牵连其它监听器，更不会让发布者失败：

```java
private void dispatch(List<EventListener> listeners, AgentEvent event) {
    if (listeners == null || listeners.isEmpty()) return;
    for (EventListener listener : listeners) {
        try {
            listener.onEvent(event);
        } catch (Exception ex) {
            log.error("[EventBus] 监听器处理事件异常 listener={}", ...);  // 隔离，不上抛
        }
    }
}
```

---

## 第三部分 · 怎么用

### 6.8 定义一个监听器

想在会话失败时发告警？加个 Listener 就够，无需碰任何发布方：

```java
@Component
public class AlertOnFailureListener implements EventListener {
    @Override public String interestedType() { return "SESSION_FAILED"; }
    @Override public void onEvent(AgentEvent event) {
        alertService.fire("会话失败: " + event.sessionId() + ", 详情: " + event.payload());
    }
}
```

Spring 启动时会自动把它注入 `EventBus` 构造函数的 `List`，完成订阅——**零配置**。

### 6.9 发布一个事件

任意模块（Runtime、Dispatcher、业务代码）在事情发生时发布：

```java
// 会话失败时
eventBus.publish(AgentEvent.of("SESSION_FAILED", session.getSessionId(), lastError));

// Step 完成时
eventBus.publish(AgentEvent.of("STEP_COMPLETED", sessionId, stepIndex));

// 全局定时到点
eventBus.publish(AgentEvent.global("DAILY_TRIGGER", "09:00"));
```

发布者发完即走，完全不关心有几个订阅者、它们干了什么。

### 6.10 全量审计监听器（通配）

```java
@Component
public class AuditListener implements EventListener {
    @Override public String interestedType() { return EventListener.WILDCARD; }  // "*"
    @Override public void onEvent(AgentEvent event) {
        auditLog.record(event.eventId(), event.type(), event.sessionId(), event.occurredAt());
    }
}
```

一个通配监听器就把全系统事件流落地成审计日志——这正是 Chapter 04 事件溯源恢复能力的数据来源。

### 6.11 事件驱动 × 任务队列的配合

两者不是二选一，而是黄金搭档：**事件负责"通知有事发生"，队列负责"可靠地把活干完"。** 典型接法——用一个监听器把事件转成任务入队：

```java
@Component
public class TrendingTriggerListener implements EventListener {
    @Override public String interestedType() { return "DAILY_TRIGGER"; }
    @Override public void onEvent(AgentEvent event) {
        // 事件只管"喊一嗓子"，真正的活扔进队列走重试/死信保障
        taskQueue.enqueue(AgentTask.immediate(newSessionId(), "CHECK_TRENDING", null));
    }
}
```

这样既有事件驱动的**即时响应与解耦**，又有任务队列的**可靠执行（重试/死信/持久化）**——最终实战 GithubTrendingAgent 正是这么串起来的。

---

## 第四部分 · 深挖一层

### 6.12 同步派发 vs 异步派发

本章 EventBus 是**同步派发**：`publish` 在调用线程上依次通知所有监听器。

- **优点**：简单、有序、易测、事务边界清晰（可与发布者同事务）。
- **缺点**：监听器慢会拖慢发布者；监听器多会串行累积延迟。

生产可演进为**异步派发**（把 `dispatch` 提交到线程池），换取发布者快速返回，但要接受"最终一致、顺序不保证、需处理背压"的代价。再进一步就是换成 Kafka/RabbitMQ——上层发布/订阅代码几乎不变，这正是面向接口 + 中枢解耦的红利。

### 6.13 为什么事件要不可变？

不可变（`record` + `Object payload` 只读引用）带来三大好处：**① 线程安全**——多个监听器并发读同一事件无需加锁；**② 可重放**——事件溯源要求历史事件永不改变，才能通过重放精确还原状态（Temporal 的确定性重放依赖此）；**③ 可追溯**——审计日志里的事件就是当时发生的真相，不会被后续篡改。

### 6.14 事件顺序与幂等

同步派发天然保证"同一发布线程内的事件有序"。但跨发布者、异步化、或消息中间件重投后，顺序无法保证、事件可能重复。对策就是 `eventId` + 幂等消费：监听器记录已处理的 `eventId`，重复到达直接跳过。这与 Chapter 05 任务的 `taskId` 去重、Chapter 08 的副作用幂等一脉相承——**分布式系统里，"至少一次投递 + 幂等消费" 是可靠性的标准组合拳**。

### 6.15 对标业界

- **Spring `ApplicationEventPublisher`**：Spring 自带的进程内事件机制，`@EventListener` 注解订阅，与本章思路一致；本章手写是为了讲透原理、且不绑定 Spring 事件语义。
- **Guava EventBus**：经典的进程内 Pub-Sub 库，`@Subscribe` 注解 + 异常隔离，与本章几乎同构。
- **Kafka / RabbitMQ**：跨进程、可持久化的事件总线，解决同步派发的所有缺点，代价是运维复杂度。
- **AWS EventBridge**：云上的事件总线 + 规则路由，把"按 type 路由"做成了托管服务。

---

## 第五部分 · 面试 / 复盘

**Q1：事件驱动解决了什么核心问题？**
A：解耦。发布者与订阅者互不引用，新增反应只需加订阅者、不改发布方（开闭原则）。同时把"一件事发生、多方响应"从臃肿的顺序代码里解放出来，让每种反应独立演进、独立测试。

**Q2：同步 EventBus 里一个监听器抛异常会怎样？如何设计？**
A：若不隔离，会中断后续监听器甚至让发布者失败。正确做法是**逐个 try-catch 隔离**（本章 `dispatch` 的实现），单个失败只记录日志，不牵连其它监听器和发布者。

**Q3：事件为什么要设计成不可变？**
A：线程安全（并发读免锁）、可重放（事件溯源精确还原状态）、可追溯（审计真相不被篡改）。可变事件在多订阅者场景下极易产生数据竞争与"读到被别人改过的值"的诡异 bug。

**Q4：事件驱动和任务队列该用哪个？**
A：不是二选一。事件擅长"即时通知、一对多广播、解耦"；队列擅长"可靠执行、重试、延迟、削峰、持久化"。生产中常用"事件触发 → 入队执行"组合：事件负责喊话，队列负责把活干完并保证不丢。

**Q5：如何保证事件不被重复处理？**
A：`eventId` + 幂等消费。监听器维护一个已处理 eventId 的集合（生产用 Redis SET 带 TTL），收到重复 eventId 直接跳过。这是"至少一次投递"语义下的标准配套，与任务 taskId 去重、副作用幂等同源。

---

> 小结：本章用三个类实现了进程内事件总线——不可变事件、自动订阅、双路由、异常隔离，让 Agent 具备了响应式解耦能力，并与 Chapter 05 的任务队列组成"通知 + 执行"黄金搭档。下一章进入**监控与日志（Monitor & Log）**，给这台永不停歇的机器装上仪表盘与黑匣子。