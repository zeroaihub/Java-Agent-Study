# Chapter 07 · 监控与日志：给永不停歇的机器装上仪表盘

> 本章目标：理解为什么"能跑"的 Agent 远远不够，必须"可观测"；掌握 Counter / Gauge 两类核心指标的语义差异；学会用一个通配事件监听器把领域事件无侵入地翻译成运行指标与结构化日志；建立"指标 + 日志 + 追踪"三支柱的可观测性心智模型，并知道如何对接 Micrometer / Prometheus / Grafana 的生产级链路。

---

## 第一部分 · 为什么要学监控与日志

想象一个场景：你的 GitHub Trending Agent 已经上线，每天 9 点自动登录、抓取、总结、推送。某天早上，运营同学在企业微信群里问："今天的 Trending 简报怎么没来？"

这时候你打开服务器，面对的是一个**已经运行了 37 天、处理了上万个任务的黑盒进程**。你想知道：

- 昨天到底跑了没有？跑到哪一步挂了？
- 是登录失败、抓取超时，还是推送被限流？
- 失败的任务重试了几次？进死信队列了吗？
- 现在还有多少会话在跑？队列里积压了多少任务？

如果你的 Agent 没有监控与日志，那么此刻你**一无所知**——只能靠 `jstack`、`top`、翻散落的 `System.out.println` 去猜。这在 Demo 里无所谓，但在生产环境是灾难。

一句话总结监控的价值：

> **短生命周期程序的失败是"事件"，长生命周期 Agent 的失败是"状态"。** 事件靠事后排查，状态必须靠实时观测。

长生命周期 Agent 与普通请求-响应服务最大的区别，就在于它**没有一个明确的"请求边界"来天然收敛日志上下文**。一个 HTTP 请求进来、处理、返回，链路清晰；而一个 Agent 会话可能横跨数小时、数十个任务、多次重试与恢复。没有主动埋设的指标与结构化日志，你根本无法回答"它现在健康吗"这个最基本的问题。

这就是本章要解决的核心问题：**如何在不污染业务代码的前提下，给这台永不停歇的机器装上一块完整的仪表盘。**

---

## 第二部分 · 监控与日志是什么

### 2.1 可观测性的三大支柱

现代可观测性（Observability）建立在三根支柱之上：

```
+-------------------------------------------------------------+
|                    Observability (可观测性)                  |
+------------------+------------------+-----------------------+
|   Metrics 指标    |    Logging 日志   |    Tracing 链路追踪    |
+------------------+------------------+-----------------------+
| 聚合的数值        | 离散的事件记录     | 一次请求的完整调用链    |
| 回答"多少/多快"   | 回答"发生了什么"   | 回答"慢在哪一环"        |
| Counter/Gauge    | INFO/WARN/ERROR   | Span + TraceId        |
| Prometheus       | ELK / Loki        | Jaeger / Zipkin       |
| 低成本、可告警    | 中成本、可检索     | 高成本、可下钻          |
+------------------+------------------+-----------------------+
```

本章聚焦前两根支柱——**指标**与**日志**，它们成本最低、收益最高，是任何 Agent 上线前的必备项。链路追踪属于进阶话题，我们在架构文档中留了对接位。

### 2.2 两类核心指标：Counter 与 Gauge

指标世界里有两个最基础的类型，理解它们的语义差异是入门第一课：

| 类型 | 语义 | 特征 | Agent 中的例子 |
|------|------|------|----------------|
| **Counter（计数器）** | 累计发生次数 | **只增不减**，重启归零 | 累计完成任务数、累计失败数、进死信数 |
| **Gauge（量表）** | 某一时刻的瞬时值 | **可增可减**，反映当前状态 | 当前运行中会话数、队列积压深度 |

一个经典的判别法则：

> **如果这个数字会"回落"，它就是 Gauge；如果它只会"往上爬"，它就是 Counter。**

"累计完成 10000 个任务"——完成的任务不会变没，所以是 Counter。
"当前有 3 个会话在跑"——会话跑完就减一，所以是 Gauge。

搞混两者会导致监控图表彻底失真：如果你把"当前会话数"做成 Counter，那图表会是一条永远上升的直线，完全看不出真实负载。

### 2.3 结构化日志：Agent 的黑匣子

飞机有黑匣子，Agent 也需要。但日志不是随手 `println`，生产级日志有三个铁律：

1. **结构化**：字段化输出（`key=value` 或 JSON），而非自由文本，否则日志平台无法检索。
2. **分级**：`INFO` 记正常流转、`WARN` 记可恢复异常、`ERROR` 记需人工介入的故障。
3. **带上下文**：每条日志必须能追溯到具体的 `sessionId` / `taskId` / `eventId`，否则海量日志里根本对不上号。

---

## 第三部分 · 怎么用：一个监听器搞定全部埋点

本项目的监控设计有一个漂亮的架构决策：**监控完全建立在 Chapter 06 的事件总线之上，业务代码零埋点。**

业务模块只管发布事件（`SESSION_STARTED`、`TASK_SUCCESS`、`TASK_DEAD`……），至于"这些事件要记什么指标、打什么日志"，全部集中在一个通配监听器里定义。这就是事件驱动架构送给我们的红利。

### 3.1 指标收集器 AgentMetrics

先看仪表盘本身——[`AgentMetrics`](../../monitor/AgentMetrics.java:24)：

```java
@Component
public class AgentMetrics {

    /** 计数器集合：name -> 累计值。 */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 量表集合：name -> 当前值。 */
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    /** 计数器 +1。 */
    public void increment(String name) {
        incrementBy(name, 1L);
    }

    /** 计数器 +delta。 */
    public void incrementBy(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    /** 量表 +delta（delta 可为负）。 */
    public void addGauge(String name, long delta) {
        gauges.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }
    // ...
}
```

这里有三个值得停下来品味的设计细节：

**其一，为什么用 `AtomicLong` 而不是普通 `long`？** 因为 Agent 是并发的——调度器、事件总线、多个任务处理线程都可能同时更新同一个指标。`counters++` 在多线程下是"读-改-写"三步操作，会丢更新；[`AtomicLong.addAndGet()`](src/main/java/com/zero/ai/agentstudy/day12longrunningagent/monitor/AgentMetrics.java:55) 用 CAS 保证了原子性。

**其二，为什么用 `computeIfAbsent`？** 它保证"若指标不存在则原子地创建，若存在则复用"，避免了两个线程同时首次写入同一指标时创建出两个 `AtomicLong` 互相覆盖的竞态。

**其三，为什么把指标名抽成常量？** 看 [`AgentMetrics`](src/main/java/com/zero/ai/agentstudy/day12longrunningagent/monitor/AgentMetrics.java:34) 顶部的 `TASK_SUCCESS`、`SESSION_STARTED` 等常量——这是为了消灭"魔法字符串"。指标名一旦散落成裸字符串，写错一个字母（`"task.sucess"`）就会悄悄多出一个永远为 0 的指标，且编译器不会报错。集中成常量后，写错立刻编译失败。

### 3.2 监控事件监听器 MonitorEventListener

有了仪表盘，还需要一个"抄表员"把事件翻译成指标。这就是 [`MonitorEventListener`](src/main/java/com/zero/ai/agentstudy/day12longrunningagent/monitor/MonitorEventListener.java:22)：

```java
@Component
public class MonitorEventListener implements EventListener {

    private final AgentMetrics metrics;

    public MonitorEventListener(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    /** 订阅所有事件。 */
    @Override
    public String interestedType() {
        return EventListener.WILDCARD;   // "*"
    }

    @Override
    public void onEvent(AgentEvent event) {
        // 每来一个事件先累加"事件发布总数"
        metrics.increment(AgentMetrics.EVENT_PUBLISHED);

        // 结构化日志：统一格式，便于日志平台检索与告警
        log.info("[Monitor] event type={}, eventId={}, sessionId={}, at={}",
                event.type(), event.eventId(), event.sessionId(), event.occurredAt());

        // 按事件类型翻译成对应业务指标
        switch (event.type()) {
            case "TASK_SUCCESS" -> metrics.increment(AgentMetrics.TASK_SUCCESS);
            case "TASK_FAILED" -> metrics.increment(AgentMetrics.TASK_FAILED);
            case "TASK_RETRIED" -> metrics.increment(AgentMetrics.TASK_RETRIED);
            case "TASK_DEAD"    -> metrics.increment(AgentMetrics.TASK_DEAD);
            case "SESSION_STARTED" -> {
                metrics.increment(AgentMetrics.SESSION_STARTED);
               metrics.addGauge(AgentMetrics.GAUGE_RUNNING_SESSIONS, 1);
            }
            case "SESSION_COMPLETED", "SESSION_FAILED" -> {
                metrics.increment(AgentMetrics.SESSION_COMPLETED);
                metrics.addGauge(AgentMetrics.GAUGE_RUNNING_SESSIONS, -1);
            }
            case "CHECKPOINT_SAVED"   -> metrics.increment(AgentMetrics.CHECKPOINT_SAVED);
            case "RECOVERY_TRIGGERED" -> metrics.increment(AgentMetrics.RECOVERY_TRIGGERED);
            default -> { /* 未知事件：只计入总数 */ }
        }
    }
}
```

请重点看两处设计：

**通配订阅 `interestedType() 返回 "*"`。** 回忆 Chapter 06，[`EventBus`](src/main/java/com/zero/ai/agentstudy/day12longrunningagent/event/EventBus.java) 会把所有事件都派发给通配监听器。监控天然需要"看到全部"，所以它是通配符最正当的用户。业务模块新增任何事件类型，监控自动"看得见"，无需改动发布方。

**Gauge 的加减配对。** 注意 `SESSION_STARTED` 时 `addGauge(..., 1)`，`SESSION_COMPLETED / SESSION_FAILED` 时 `addGauge(..., -1)`。这一加一减，让 `GAUGE_RUNNING_SESSIONS` 精确反映"当前有多少会话在跑"。这正是 Gauge 与 Counter 的本质区别落到代码上的样子——**每一次 +1 都必须有对应的 -1，否则量表就会泄漏。**

### 3.3 数据流：一个事件如何变成一格仪表

```
业务模块                EventBus            MonitorEventListener        AgentMetrics
   |                       |                        |                       |
   | publish(TASK_SUCCESS) |                        |                       |
   |---------------------->|                        |                       |
   |                       | dispatch(event)        |                       |
   |                       | (命中通配监听器 "*")    |                       |
   |                       |----------------------->|                       |
   |                       |                        | increment(EVENT_PUBLISHED)
   |                       |                        |---------------------->| counters["event.published"]++
   |                       |                        | log.info("[Monitor]..")|
   |                       |                        | increment(TASK_SUCCESS)|
   |                       |                        |---------------------->| counters["task.success"]++
   |                       |                        |                       |
   |                       |         (异常隔离：单个监听器抛错不影响其他)      |
   v                       v                        v                       v
```

整条链路的精妙之处在于：**业务模块对监控的存在一无所知**。它只是"发了个事件"，剩下的记指标、打日志全部自动发生。这就是事件驱动 + 通配监听器组合出的"无侵入可观测性"。

---

## 第四部分 · 深挖一层

### 4.1 快照：如何把指标暴露出去

指标记在内存里还不够，得能被外部读取。[`AgentMetrics.snapshot()`](src/main/java/com/zero/ai/agentstudy/day12longrunningagent/monitor/AgentMetrics.java:87) 提供了一份只读快照：

```java
public Map<String, Long> snapshot() {
    Map<String, Long> snap = new java.util.LinkedHashMap<>();
    counters.forEach((k, v) -> snap.put(k, v.get()));
    gauges.forEach((k, v) -> snap.put(k, v.get()));
    return Map.copyOf(snap);   // 不可变，防止调用方篡改内部状态
}
```

`Map.copyOf` 返回不可变映射——这是**防御性拷贝**的又一次应用（我们在 Chapter 03 状态管理里已见过）。监控端点、定时日志、REST API 都可以安全地拿这份快照去展示，绝不会误改到内部计数器。

### 4.2 从进程内指标到 Prometheus

本项目的 `AgentMetrics` 是"最小可用"的进程内实现，够教学、够跑通。但生产环境的标准链路是 **Micrometer + Prometheus + Grafana**：

```
                                            (pull, 每 15s 抓一次)
AgentMetrics ---> MeterRegistry ---> /actuator/prometheus <--------- Prometheus
 (业务埋点)      (Micrometer 门面)    (Spring Boot 暴露端点)          (时序数据库)
                                                                        |
                                                                        v
                                                                    Grafana
                                                                   (可视化+告警)
```

迁移路径非常平滑：把 `AgentMetrics` 的 `increment` 内部实现从 `AtomicLong` 换成 `meterRegistry.counter(name).increment()`，`addGauge` 换成 Micrometer 的 `Gauge`——**对外接口不变，所有调用方（`MonitorEventListener`）无需改动**。这正是我们一开始就把指标操作收敛进 `AgentMetrics` 门面的回报：面向接口编程让底层实现可插拔。

Micrometer 之于监控，就像 SLF4J 之于日志——它是一个"门面（Facade）"，屏蔽了后端（Prometheus / Datadog / CloudWatch）的差异。

### 4.3 关键告警指标该怎么设

装了仪表盘，还得知道盯哪几个表。对长生命周期 Agent，以下告警规则是生产经验的结晶：

| 告警项 | 基于的指标 | 阈值示例 | 说明 |
|--------|-----------|---------|------|
| 死信激增 | `task.dead`（Counter 增速） | 5 分钟内 > 0 | 进死信意味着有任务彻底失败，必须人工介入 |
| 队列积压 | `gauge.queue.depth`（Gauge） | 持续 > 100 | 消费速度跟不上生产，可能卡死或过载 |
| 会话泄漏 | `gauge.running.sessions`（Gauge） | 只增不减 | 说明有会话没正常收尾，Gauge 加减失配 |
| 重试风暴 | `task.retried` / `task.success` 比值 | > 30% | 大量重试说明下游不稳定 |
| 心跳停摆 | `event.published`（Counter 增速） | 5 分钟内无增长 | Agent 可能已假死 |

注意最后一条"心跳停摆"：对**永不停歇**的 Agent 而言，"指标不再增长"本身就是最危险的信号——它可能已经死了却没抛任何异常。这是长生命周期系统特有的"沉默故障"，普通请求-响应服务不会遇到。

---

## 第五部分 · 面试复盘

**Q1：Counter 和 Gauge 有什么区别？请各举一个 Agent 场景。**

Counter 只增不减，用于累计次数，如"累计完成任务数 `task.success`"；Gauge 可增可减，反映瞬时状态，如"当前运行中会话数 `gauge.running.sessions`"。判别口诀：会回落的是 Gauge，只上升的是 Counter。搞混会导致图表失真——把当前会话数做成 Counter，会得到一条永远上升的直线。

**Q2：为什么监控指标要用 `AtomicLong` 而不是普通 `long`？**

因为 Agent 是并发环境，调度器、事件总线、多个任务线程可能同时更新同一指标。`count++` 是"读-改-写"三步非原子操作，并发下会丢更新。`AtomicLong.addAndGet()` 基于 CAS 保证原子性。配合 `ConcurrentHashMap.computeIfAbsent` 保证指标的原子创建，整套指标中心才是并发安全的。

**Q3：本项目的监控为什么不需要在业务代码里埋点？**

因为监控构建在事件总线之上。[`MonitorEventListener`](src/main/java/com/zero/ai/agentstudy/day12longrunningagent/monitor/MonitorEventListener.java:22) 用通配符 `"*"` 订阅所有事件，业务模块只管发布领域事件，"记什么指标、打什么日志"集中在这一个监听器里定义。新增事件类型时监控自动感知，实现了监控与业务的彻底解耦——这是事件驱动架构的直接红利。

**Q4：`snapshot()` 为什么返回 `Map.copyOf` 而非直接返回内部 Map？**

防御性拷贝。若直接返回内部集合的引用，调用方可能误改（甚至恶意改）内部计数器，破坏指标一致性。`Map.copyOf` 返回不可变副本，读方只能读、不能写，内外隔离。这与 Chapter 03 状态快照用不可变对象是同一设计哲学。

**Q5：一个"永不停歇"的 Agent 假死了却没抛异常，你怎么发现？**

靠"心跳指标停止增长"这个信号。对长生命周期系统，指标不再上升本身就是告警条件——比如 `event.published` 这个 Counter 若 5 分钟无增长，说明 Agent 已停止处理。这是长生命周期系统特有的"沉默故障"，只能靠对指标增速的主动监控（rate 告警）发现，无法靠"等它报错"发现。

---

## 本章小结

- 监控回答"它现在健康吗"，日志回答"它刚才发生了什么"，二者是长生命周期 Agent 上线的必备项。
- 两类核心指标：Counter（只增不减，累计次数）与 Gauge（可增可减，瞬时状态），配对加减是 Gauge 不泄漏的关键。
- [`AgentMetrics`](src/main/java/com/zero/ai/agentstudy/day12longrunningagent/monitor/AgentMetrics.java:24) 用 `AtomicLong + ConcurrentHashMap` 实现并发安全的进程内指标中心，接口稳定、底层可平滑迁移到 Micrometer/Prometheus。
- [`MonitorEventListener`](src/main/java/com/zero/ai/agentstudy/day12longrunningagent/monitor/MonitorEventListener.java:22) 用通配订阅把事件无侵入地翻译成指标与结构化日志，是事件驱动架构的可观测性红利。
- 生产告警要盯：死信激增、队列积压、会话泄漏、重试风暴、心跳停摆——最后一条是长生命周期系统的独门风险。

下一章，我们把前七章所有能力——生命周期、状态、检查点、恢复、调度、队列、重试、死信、事件、监控——拼装成一个真正的 **AgentRuntime 总控**，并落地 **GitHub Trending Agent** 综合实战。