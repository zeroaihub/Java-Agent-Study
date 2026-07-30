# Chapter 05 · 调度、任务队列、重试与死信队列

> 本章目标：讲透 Long Running Agent 的"动力系统"——如何用**任务队列**驱动执行、用**调度器**定时触发、用**重试策略**对抗瞬时故障、用**死信队列**隔离毒丸消息。给出 `RetryPolicy`、`AgentTask`、`TaskQueue`、`InMemoryTaskQueue`、`DeadLetter`、`DeadLetterQueue`、`TaskHandler`、`TaskDispatcher`、`AgentScheduler` 九个完整可运行代码。

---

## 第一部分 · 为什么学

### 5.1 从"一条直线"到"一池任务"

Day01–Day11 的 Agent 大多是"请求—响应"式的：来一个问题，跑一条链，返回一个答案。但长生命周期 Agent 的执行形态完全不同——它更像一个**永不停歇的工厂**：

- 有的任务现在就要跑（立即任务）；
- 有的任务要等到明天 9 点才跑（定时任务）；
- 有的任务失败了，要过 2 秒再试一次（延迟重试任务）；
- 有的任务反复失败，必须请出流水线单独处理（死信）。

把这些异构的执行需求塞进一条直线里是灾难。正确的抽象是：**把每个执行单元建模成一个"任务"（Task），扔进"任务队列"（Queue），由"调度器"（Scheduler）按节奏取出、分发、执行。** 执行体本身无状态，状态都在任务和会话里——这正好接续 Chapter 03 的"状态外置"思想。

### 5.2 可靠性三大敌人

一旦任务开始异步执行，三个敌人立刻出现：

1. **瞬时故障**：网络抖动、下游 503、限流。这类错误**重试一下大概率就好**——但盲目立即重试会形成"重试风暴"。对策：**指数退避 + 抖动**。
2. **永久故障**：参数错误、业务规则拒绝、Bug。这类错误**重试一万次也没用**，只会堵塞队列、拖垮系统。对策：**重试耗尽后进死信队列**。
3. **任务丢失**：消费者取走任务后崩溃，任务凭空消失。对策：**至少一次投递（poll + 显式 ack）**。

本章的九个类，就是为了系统性地干掉这三个敌人。

### 5.3 类比：餐厅后厨

- **任务队列** = 订单夹子上的一排小票（先进先出，延迟单挂后面）。
- **调度器** = 主厨喊单的节奏（每隔几秒看一眼有没有到点的单）。
- **TaskHandler** = 不同灶台的厨师（炒菜的、凉菜的、甜品的，按菜品类型分发）。
- **重试** = 一道菜炒糊了，重新炒（但不能同一秒把十道糊菜一起重炒）。
- **死信队列** = 客人点了个后厨根本做不了的菜，单独挑出来交给经理处理，而不是卡住整条流水线。

---

## 第二部分 · 是什么

### 5.4 整条流水线的数据流

```
                        ┌─────────────────────────────────────┐
   业务/定时投递  ─────► │            TaskQueue                 │
   AgentTask.immediate  │  (按 visibleAt 排序的优先级队列)      │
   AgentTask.delayed    │  队头 = 最早该执行的任务              │
                        └──────────────┬──────────────────────┘
                                       │ poll() 只返回已到期任务
                                       ▼
   AgentScheduler.heartbeat()  ──► TaskDispatcher.tick()
   (@Scheduled 每 500ms)             │
                                     │ 按 task.type 路由
                                     ▼
                              ┌─────────────┐
                              │ TaskHandler │  handle(task)
                              └──────┬──────┘
                         成功        │        抛异常
                    ┌───────────────┴───────────────┐
                    ▼                                ▼
              queue.ack(id)              RetryPolicy.canRetry(attempts)?
              (彻底移除)                   ├── 是 ─► queue.requeue(id, 退避delay)
                                          └── 否 ─► DeadLetterQueue.put(task, reason)
                                                    + queue.ack(id)
```

一句话：**队列负责"存与序"，调度器负责"触发节奏"，Dispatcher 负责"成功/重试/死信"的决策，Handler 负责"具体干活"。** 职责清晰、单一。

### 5.5 任务模型 AgentTask

任务是队列的最小流转单元。关键字段：

- `taskId`：全局唯一，用于去重、ack、追踪。
- `sessionId`：归属哪个长任务会话（连回 Chapter 03 的 AgentSession）。
- `type`：分发路由键，决定交给哪个 Handler。
- `visibleAt`：**可见时间**——延迟/定时的核心。`poll()` 只返回 `visibleAt <= now` 的任务。
- `attempts`：已尝试次数，配合 RetryPolicy 判断是否进死信。

来看真实代码（`AgentTask.java`）：

```java
public class AgentTask {
    private final String taskId;
    private final String sessionId;
    private final String type;
    private final String payload;
    private volatile Instant visibleAt;   // 延迟/定时的核心
    private volatile int attempts;
    private final Instant createdAt;

    /** 立即可见的任务。 */
    public static AgentTask immediate(String sessionId, String type, String payload) {
        return new AgentTask(sessionId, type, payload, Instant.now());
    }

    /** 延迟 delayMillis 毫秒后可见的任务。 */
    public static AgentTask delayed(String sessionId, String type, String payload, long delayMillis) {
        return new AgentTask(sessionId, type, payload, Instant.now().plusMillis(delayMillis));
    }

    /** 当前时刻任务是否可见（可被消费）。 */
    public boolean isVisible(Instant now) {
        return !visibleAt.isAfter(now);
    }
}
```

两个静态工厂 `immediate` / `delayed` 让"立即执行"和"延迟执行"一目了然。`visibleAt` 与 `attempts` 用 `volatile` 修饰，保证跨线程可见性（调度线程改、消费线程读）。

### 5.6 队列抽象 TaskQueue

面向接口编程，四个核心动作 + 一个观测方法：

```java
public interface TaskQueue {
    void enqueue(AgentTask task);            // 入队
    Optional<AgentTask> poll();              // 拉取一个"已到期"的任务
    void ack(String taskId);                 // 处理成功，彻底移除
    void requeue(String taskId, long delayMillis); // 处理失败，延迟后重新可见
    int size();
}
```

`poll` + `ack` 的组合实现"至少一次投递"：**取走不等于删除，只有显式 ack 才删除。** 这样即使消费中途崩溃，任务仍在（生产级实现会做"处理中超时自动重投"，内存版做了简化）。`requeue` 是重试退避的落地点——把失败任务的 `visibleAt` 推到未来，它自然就不会被立刻 poll 到。

### 5.7 内存队列 InMemoryTaskQueue

延迟/定时任务如何实现？答案是一个**按 `visibleAt` 排序的小根堆**。队头永远是"最早该执行"的任务，poll 时只需看队头是否到期：

```java
@Component
public class InMemoryTaskQueue implements TaskQueue {

    // 按可见时间升序的优先级队列（小根堆）
    private final PriorityBlockingQueue<AgentTask> queue =
            new PriorityBlockingQueue<>(64, Comparator.comparing(AgentTask::getVisibleAt));

    // taskId → task 索引，支持 ack/requeue 快速定位
    private final ConcurrentHashMap<String, AgentTask> index = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentTask> poll() {
        Instant now = Instant.now();
        AgentTask head = queue.peek();
        if (head == null || !head.isVisible(now)) {   // 队头未到期 => 无可消费任务
            return Optional.empty();
        }
        AgentTask task = queue.poll();
        if (task == null) return Optional.empty();
        task.incrementAttempts();                       // 取出即视为一次尝试
        return Optional.of(task);                        // index 仍保留（处理中）
    }

    @Override
    public void requeue(String taskId, long delayMillis) {
        AgentTask task = index.get(taskId);
        if (task == null) return;                        // 已 ack 或从未入队
        task.setVisibleAt(Instant.now().plusMillis(Math.max(delayMillis, 0)));
        queue.offer(task);                               // 延迟后重新可见
    }
}
```

**关键设计**：`PriorityBlockingQueue` 本身线程安全，再叠一个 `ConcurrentHashMap` 作为 `taskId → task` 索引，让 `ack`/`requeue` 能 O(1) 定位。`poll` 用 `peek` 先探队头到期与否，避免误取未来的任务——这是延迟队列最小可用实现的精髓。

> 局限提示：内存版进程重启即丢、无持久化、无消费者组。这正是 `TaskQueue` 面向接口的意义——生产环境把这一个 `@Component` 换成 Redis ZSet / RabbitMQ 延迟插件 / Kafka 即可，上层 Dispatcher/Scheduler 一行不改。

### 5.8 重试策略 RetryPolicy —— 指数退避 + 抖动

对抗瞬时故障的标准武器。核心公式：

```
delay = min(baseDelay * 2^(attempt-1), maxDelay) + random(0, jitter)
```

- `2^(attempt-1)`：**指数增长**，第 1 次退避 1s，第 2 次 2s，第 3 次 4s……让下游有喘息时间。
- `min(..., maxDelay)`：**封顶**，防止指数爆炸导致等待过久。
- `+ random(0, jitter)`：**抖动**，打散并发峰值，避免所有失败任务在同一毫秒齐刷刷重试（重试风暴）。

真实代码（`RetryPolicy.java`）：

```java
public long nextDelayMillis(int attempt) {
    if (attempt < 1) attempt = 1;
    // 2^(attempt-1)，位移实现，注意防止溢出
    long exp = attempt - 1 >= 62 ? Long.MAX_VALUE : (1L << (attempt - 1));
    long backoff;
    if (exp > maxDelayMillis / Math.max(baseDelayMillis, 1)) {   // 溢出保护
        backoff = maxDelayMillis;
    } else {
        backoff = Math.min(baseDelayMillis * exp, maxDelayMillis);
    }
    long jitter = jitterMillis <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMillis);
    return backoff + jitter;
}
```

`canRetry(attempt)` 则简单判断 `attempt <= maxRetries`。默认策略 `defaultPolicy()`：最多 3 次、基础 1s、上限 30s、抖动 1s——适配大多数场景。注意用 `1L << (attempt-1)` 位移代替 `Math.pow`，并做了两处**溢出保护**，这是生产代码与 Demo 的分水岭。

### 5.9 死信队列 DeadLetterQueue —— 隔离毒丸

重试耗尽仍失败的任务，就是"毒丸消息"（poison message）。它既不能静默丢弃（丢了没人知道），也不能无限重试（拖垮系统），唯一正确的归宿是**死信队列**：

```java
@Component
public class DeadLetterQueue {
    private final List<DeadLetter> letters = new CopyOnWriteArrayList<>();

    public void put(DeadLetter letter) {
        letters.add(letter);
        log.error("[DLQ] 任务进入死信队列 taskId={}, sessionId={}, attempts={}, reason={}",
                letter.task().getTaskId(), letter.task().getSessionId(),
                letter.totalAttempts(), letter.reason());
    }
    public int size() { return letters.size(); }   // 监控告警的关键指标
}
```

死信记录用不可变 `record` 承载（`DeadLetter.java`），保留**原任务 + 失败原因 + 累计尝试次数 + 死亡时间**四要素，供人工排查或后续重放。生产实践中，`DLQ.size() > 0` 应直接触发告警——死信不是终点，而是需要人介入的信号。

### 5.10 分发器 TaskDispatcher —— 决策中枢

前面所有零件在这里拼装。构造函数用 Spring 的 `List<TaskHandler>` 注入所有处理器，自动构建 `type → handler` 路由表（策略模式 + 开闭原则：新增任务类型只加 Handler，不改主循环）：

```java
public TaskDispatcher(TaskQueue taskQueue, DeadLetterQueue deadLetterQueue,
                      RetryPolicy retryPolicy, List<TaskHandler> handlerBeans) {
    Map<String, TaskHandler> map = handlerBeans.stream()
            .collect(Collectors.toMap(TaskHandler::supportType, Function.identity(),
                    (a, b) -> { throw new IllegalStateException("重复的任务类型 handler: " + a.supportType()); }));
    this.handlers.putAll(map);
}
```

核心的成功/重试/死信决策在 `onFailure`：

```java
private void onFailure(AgentTask task, Exception ex) {
    int attempts = task.getAttempts();      // poll 时已 +1，代表"已尝试次数"
    if (retryPolicy.canRetry(attempts)) {
        long delay = retryPolicy.nextDelayMillis(attempts);
        taskQueue.requeue(task.getTaskId(), delay);          // 退避后重投
    } else {
        deadLetterQueue.put(task, ex.toString());            // 重试耗尽 → 死信
        taskQueue.ack(task.getTaskId());                     // 从主队列移除
    }
}
```

还有一个易被忽视的健壮性细节：**找不到 handler 的任务直接进死信**，而非无限循环——避免一个投递错误的任务把队列堵死。

### 5.11 调度器 AgentScheduler —— 心跳驱动

队列不会自己动，需要有人"喊单"。`AgentScheduler` 用 Spring `@Scheduled` 提供心跳：

```java
@Component
public class AgentScheduler {
    private static final int MAX_DRAIN_PER_TICK = 50;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Scheduled(fixedDelayString = "${zero.agent.scheduler.tick-delay-ms:500}")
    public void heartbeat() {
        if (!running.get()) return;
        int processed = 0;
        try {
            while (processed < MAX_DRAIN_PER_TICK && dispatcher.tick()) {
                processed++;
            }
        } catch (Exception ex) {
            log.error("[Scheduler] 心跳处理异常", ex);   // 心跳线程绝不能因单次异常而死
        }
    }
}
```

三个生产级考量：**① `fixedDelay` 而非 `fixedRate`**——上一轮跑完再等间隔，天然背压不堆叠；**② `MAX_DRAIN_PER_TICK` 限流**——一轮最多排空 50 个，避免霸占调度线程饿死其它定时任务；**③ `running` 开关**——可优雅暂停消费（`pause()`/`resume()`）而不停进程，运维发版时很有用。

> ⚠️ 注意：`@Scheduled` 需在配置类上开启 `@EnableScheduling` 才生效（见后续 Day12RuntimeConfig）。

---

## 第三部分 · 怎么用

### 5.12 端到端最小示例

假设我们要处理一个"发送企业微信通知"的任务：

```java
// 1) 实现一个 Handler（Spring 会自动注入 Dispatcher）
@Component
public class NotifyHandler implements TaskHandler {
    @Override public String supportType() { return "NOTIFY"; }
    @Override public void handle(AgentTask task) throws Exception {
        // 调用企业微信 API；失败时抛异常，Dispatcher 会按策略重试
        wechatClient.send(task.getPayload());
    }
}

// 2) 业务代码投递任务
taskQueue.enqueue(AgentTask.immediate(sessionId, "NOTIFY", "今日 Trending 总结..."));

// 3) 剩下的交给心跳：Scheduler 自动 poll → Dispatcher 分发给 NotifyHandler
```

### 5.13 定时任务怎么投

"每天 9 点检查 GitHub Trending"——在某个 `@Scheduled(cron="0 0 9 * * *")` 方法里，向队列 `enqueue` 一个 `type="CHECK_TRENDING"` 的任务即可。定时"触发"与任务"执行"解耦：cron 只管按点投递，执行、重试、死信全走统一流水线。

### 5.14 延迟重试的自洽

无需额外定时器：任务失败 → `requeue(id, delay)` 把 `visibleAt` 推后 → 心跳每 500ms poll 一次，到点自然被取出重试。**延迟能力天然内建在队列的 `visibleAt` 排序里**，这是本设计最优雅之处。

---

## 第四部分 · 深挖一层

### 5.15 attempts 到底在哪 +1？

一个易错点：`attempts` 在 `InMemoryTaskQueue.poll()` 里 `incrementAttempts()`，即**"取出即计一次尝试"**。所以 `onFailure` 里读到的 `attempts` 已经包含本次失败。`canRetry(attempts)` 判断 `attempts <= maxRetries`：默认 maxRetries=3，则第 1/2/3 次失败都会重试，第 4 次（attempts=4）失败才进死信——总共执行 4 次（1 首次 + 3 重试）。理解这个计数时机，才能算准重试次数。

### 5.16 为什么 tick 而不是 while(true)？

`TaskDispatcher.tick()` 只处理一个任务、`AgentScheduler` 负责循环。这种拆分带来：**可测**（单测直接调 `tick()` 验证一次决策，无需起线程）、**可控**（心跳间隔、每轮上限都可配）、**不占 CPU**（无空转自旋）。把"做什么"和"何时做/做多少"分离，是并发代码的通用美学。

### 5.17 对标业界

- **RabbitMQ**：TTL + DLX（死信交换机）实现延迟与死信，与本章 `visibleAt` + `DeadLetterQueue` 一一对应。
- **AWS SQS**：`VisibilityTimeout`（处理中不可见）+ `maxReceiveCount` 超限进 DLQ，与 `poll/ack` + `RetryPolicy` 同构。
- **Kafka**：无原生延迟，常用"分级 topic + 消费重投"模拟，本质仍是 `requeue`。
- **Temporal**：内建 RetryPolicy（initialInterval / backoffCoefficient / maximumInterval），字段几乎与本章 `RetryPolicy` 逐一对应——说明我们的抽象是业界共识。

---

## 第五部分 · 面试 / 复盘

**Q1：为什么重试要加抖动（jitter）？**
A：纯指数退避下，同一时刻失败的一批任务会在同一未来时刻齐刷刷重试，形成周期性尖峰，反复冲击下游。抖动把重试时间随机打散到一个区间，削平尖峰，这是 AWS 论文《Exponential Backoff And Jitter》的经典结论。

**Q2：任务失败一定要重试吗？**
A：不一定。应区分**可重试错误**（网络超时、503、限流——瞬时）与**不可重试错误**（参数非法、鉴权失败、业务拒绝——永久）。理想设计是让 Handler 抛不同异常类型，Dispatcher 对不可重试错误直接进死信，不浪费重试配额。本章为简化统一重试，进阶可按异常类型分流。

**Q3：`fixedRate` 和 `fixedDelay` 有何区别？为什么选后者？**
A：`fixedRate` 按固定频率触发，不管上一次是否跑完，慢任务会导致执行堆叠、线程耗尽；`fixedDelay` 是"上一次结束后再等间隔"，天然背压、不堆叠。消费型心跳选 `fixedDelay` 更安全。

**Q4：如果消费者取走任务后进程崩了，任务会丢吗？**
A：取决于 ack 语义。本章 `poll` 只取走不删除（保留在 index 中），需显式 `ack` 才删——这是"至少一次投递"。内存版崩溃仍会丢（无持久化），但接口语义已为生产实现（如 SQS 的 VisibilityTimeout 到期自动重投）留好了口子。

**Q5：死信队列里的任务后续怎么办？**
A：三条路：① 人工排查修复根因后**重放**（re-drive）回主队列；② 确认是脏数据直接丢弃并记录；③ 触发告警让值班介入。关键是死信**必须被消费/清理**，否则 DLQ 无限增长同样是隐患。

---

> 小结：本章用九个类搭起了 Agent 的"动力系统"——队列存序、调度心跳、退避重试、死信隔离，系统性干掉了瞬时故障、永久故障、任务丢失三大敌人。下一章我们进入**事件驱动（Event Driven）**，让 Agent 从"轮询驱动"进化到"事件驱动"，实现真正的响应式长任务编排。