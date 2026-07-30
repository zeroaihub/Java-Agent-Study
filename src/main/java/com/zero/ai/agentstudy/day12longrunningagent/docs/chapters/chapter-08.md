# Chapter 08 · 综合实战：把十一个零件总装成一台永不停歇的机器

> 本章目标：把前面各章造好的独立零件（状态机、会话、检查点、恢复、重试、队列、调度、事件、监控）真正组装成一个可运转的整体；理解 AgentRuntime 作为"总控"如何用一致动作序列强制状态一致性；掌握"单步驱动 + 事件续投"如何把一个长流程拆成可检查点、可恢复、可重试的小步；通过 GitHub Trending Agent 完整端到端案例，串起崩溃恢复、重试降级、监控埋点的全链路；最后用一组 REST 端点把这台机器变得"看得见、点得动"。

---

## 第一部分 · 为什么需要一次总装

到上一章为止，我们已经造好了长生命周期 Agent 的**所有零件**：

- `AgentStateMachine`：谁能变成谁（合法流转）
- `AgentSession` / `AgentContext`：长任务的身份与进度
- `SessionStore`：状态外置，崩溃可恢复
- `CheckpointManager`：进度打点
- `RecoveryService`：崩溃后扫描非终态会话续跑
- `RetryPolicy` / `TaskDispatcher` / `DeadLetterQueue`：重试、超时、死信
- `TaskQueue` / `AgentScheduler`：任务队列与消费心跳
- `EventBus` / `AgentEvent`：事件驱动
- `AgentMetrics` / `MonitorEventListener`：可观测性

但**一堆零件不是一台机器**。如果没有一个"总控"来规定它们如何协作，业务代码就会各自为政：有人改了状态却忘了落库，有人落了库却忘了发事件，有人推进了一步却忘了打点。这些遗漏在 Demo 里看不出问题，一旦崩溃恢复就会暴露成"进度对不上、状态不一致、指标不准"的灾难。

一句话概括本章的核心命题：

> **零件保证"每个动作正确"，总控保证"动作序列一致"。** 长生命周期 Agent 的可靠性，恰恰藏在"一致"这两个字里。

---

## 第二部分 · 总控 AgentRuntime 是什么

`AgentRuntime` 是全书能力的"总装车间"。它注入四个最关键的零件，对外提供长任务的**统一操作入口**：

```
                    +---------------------------+
                    |       AgentRuntime        |
         |        (总控 @Service)      |
                    +-------------+-------------+
                                  |
        +-------------+-----------+-----------+-------------+
        |             |                       |             |
        v             v                       v             v
 AgentStateMachine  SessionStore     CheckpointManager   EventBus
   (校验流转)        (状态落库)         (进度打点)         (广播事件)
```

它最重要的职责不是"多做事"，而是**把每一次状态变更强制收敛成同一套动作序列**，绝不允许业务代码绕过。

### 2.1 核心不变式：状态变更的"一致动作序列"

任何一次状态流转，都必须依次走完这五步——这被收敛进唯一入口 `transitionTo`：

```
   transitionTo(session, to, eventType)
        |
        | 1) stateMachine.transit(from, to)   校验合法性，非法立即抛异常
        | 2) session.setState(to)             校验通过才写入内存态
        | 3) sessionStore.save(session)       立刻落库（状态外置）
        | 4) eventBus.publish(event)          广播事件（驱动监控/下游）
        v
   （日志记录流转轨迹）
```

对应真实代码（`runtime/AgentRuntime.java`）：

```java
public void transitionTo(AgentSession session, AgentState to, String eventType) {
    AgentState from = session.getState();
    stateMachine.transit(from, to);      // 1) 校验：非法流转在此抛出，绝不写入
    session.setState(to);                // 2) 写内存态
    sessionStore.save(session);          // 3) 状态外置：立刻落库
    eventBus.publish(AgentEvent.of(eventType, session.getSessionId(), to.name())); // 4) 广播
    log.info("[Runtime] 状态流转 sessionId={} {} -> {}", session.getSessionId(), from, to);
}
```

**为什么必须收敛进一个方法？** 因为一致性不能靠"程序员的自觉"。只要留一个后门允许直接 `session.setState()`，就一定有人在某个深夜的 hotfix 里用了它——然后崩溃恢复时进度就对不上。把五步锁进 `transitionTo`，就是用**架构约束**代替**口头约定**。

在此之上，Runtime 提供了一层语义化封装，让调用方读起来像自然语言：

```java
public void start(AgentSession session)    { transitionTo(session, RUNNING,   "SESSION_STARTED");   }
public void suspend(AgentSession session)  { transitionTo(session, SUSPENDED, "SESSION_SUSPENDED"); }
public void resume(AgentSession session)   { transitionTo(session, RUNNING,   "SESSION_RESUMED");   }
public void complete(AgentSession session) { transitionTo(session, COMPLETED, "SESSION_COMPLETED"); }
public void fail(AgentSession session, String reason) {
    session.setLastError(reason);
    transitionTo(session, FAILED, "SESSION_FAILED");
}
```

### 2.2 打点：完成一步后保存进度

状态流转管"生命周期"，`advanceAndCheckpoint` 管"执行进度"：

```java
public int advanceAndCheckpoint(AgentSession session) {
    int step = session.getContext().advance();   // 进度指针 +1
    sessionStore.save(session);                   // 落库
    checkpointManager.snapshot(session);          // 打检查点
    session.heartbeat();                          // 刷新心跳
    eventBus.publish(AgentEvent.of("CHECKPOINT_SAVED", session.getSessionId(), step));
    return step;
}
```

**为什么先 advance 再 snapshot？** 因为检查点记录的是"我已经完成到第几步"。先推进指针再落点，崩溃恢复时才能从"下一步"续跑，最多只丢当前正在执行的这一步——这就是"至少一次"语义的进度保证。

---

## 第三部分 · 怎么装配：让零件在 Spring 容器里各就各位

零件要能协作，第一步是让 Spring 容器正确地实例化并注入它们。大多数零件我们直接打了 `@Component` / `@Service` 让容器自动扫描，但有两个是**纯领域对象（POJO）**——`AgentStateMachine` 和 `RetryPolicy`。它们不该被 Spring 注解污染（否则就无法在单元测试里 `new` 出来独立测试），因此在 `Day12RuntimeConfig` 里显式注册为 Bean：

```java
@Configuration
@EnableScheduling                       // ← 没有它，所有 @Scheduled 心跳都不会跳
public class Day12RuntimeConfig {

    @Bean
    public AgentStateMachine agentStateMachine() {
        return new AgentStateMachine();
    }

    @Bean
    public RetryPolicy retryPolicy() {
        return RetryPolicy.defaultPolicy();
    }
}
```

这里有两个极易踩坑的决策：

1. **`@EnableScheduling` 是心跳的总开关。** `AgentScheduler.heartbeat()`（消费心跳）和 `TrendingScheduler.triggerDaily()`（每日触发）都靠 `@Scheduled` 生效。忘了这个注解，机器会"看起来启动了，但永远不干活"——这是最难排查的一类 bug，因为没有任何报错。
2. **POJO 不打注解、在 Config 里 `@Bean`。** 保持领域对象的纯粹性：`new AgentStateMachine()` 可以在任何测试里独立构造，不依赖 Spring 上下文。这是"依赖注入"与"领域纯粹"之间的平衡点。

装配完成后，`EventBus` 的构造函数会自动收集容器里所有 `EventListener` 实现并订阅——`MonitorEventListener`（监控埋点）和 `TrendingScheduler`（事件续投）就这样被自动接入事件总线，无需任何手动 `register` 调用。这就是 Spring 集合注入的威力：**新增一个监听器，只要它是 `@Component` 且实现 `EventListener`，就自动生效。**

---

## 第四部分 · 怎么跑起来：单步驱动 + 事件续投

现在零件都就位了，核心问题来了：一个"登录 → 抓取 → 总结 → 推送"的长流程，**该怎么执行**？

最朴素的写法是一个方法里顺序调四步。但这样有致命缺陷：中间任何一步崩溃，整个流程从头再来；无法在某一步之间打检查点；无法对单独某一步做重试或限流。

正确答案是 **"单步驱动 + 事件续投"**：把长流程拆成一个个独立的小步，每次只执行一步，执行完发一个"下一步就绪"的事件，由监听器把下一步入队。

### 4.1 GithubTrendingHandler：每次只推进一步

```java
@Component
public class GithubTrendingHandler implements TaskHandler {

    public static final String AGENT_TYPE = "github-trending";
    public static final String TASK_TYPE  = "github-trending-step";
    private static final String[] STEPS = {"LOGIN", "FETCH", "SUMMARIZE", "NOTIFY"};

    @Override
    public void handle(AgentTask task) throws Exception {
        AgentSession session = runtime.find(task.getSessionId()).orElseThrow();
        int stepIndex = session.getContext().getStepIndex();

        if (stepIndex >= STEPS.length) {          // 全部步骤已完成
            runtime.complete(session);            // -> COMPLETED（终态）
            return;
        }

        String step = STEPS[stepIndex];
        switch (step) {                           // 分派到对应桩逻辑
            case "LOGIN"     -> stub.login();
            case "FETCH"     -> session.getContext().put("repos", stub.fetchTrending());
            case "SUMMARIZE" -> session.getContext().put("summary",
                                    stub.summarize(session.getContext().get("repos")));
            case "NOTIFY"    -> stub.notifyWeCom(session.getContext().get("summary"));
        }

        runtime.advanceAndCheckpoint(session);    // 推进 + 打点 + 心跳
        eventBus.publish(AgentEvent.of("TASK_SUCCESS", session.getSessionId(), step));

        if (session.getContext().getStepIndex() >= STEPS.length) {
            runtime.complete(session);            // 最后一步完成即收官
        } else {
            eventBus.publish(AgentEvent.of("NEXT_STEP_READY", session.getSessionId(), ""));
        }
    }
}
```

**每一次 `handle` 只做一件事**：取当前步 → 执行 → 打点 → 要么收官、要么发"下一步就绪"事件。这就是"单步驱动"。

### 4.2 TrendingScheduler：双引擎驱动

`TrendingScheduler` 同时扮演两个角色，对应长生命周期 Agent 的两种驱动力：

```java
@Component
public class TrendingScheduler implements EventListener {

    // 引擎一：定时触发（周期任务的入口，机器自己醒来干活）
    @Scheduled(cron = "${zero.agent.trending.cron:0 0 9 * * *}")
    public void triggerDaily() {
        AgentSession session = runtime.createSession(GithubTrendingHandler.AGENT_TYPE);
        runtime.start(session);
        enqueueStep(session);
    }

    // 引擎二：事件续投（订阅 NEXT_STEP_READY，把下一步入队）
    @Override public String interestedType() { return "NEXT_STEP_READY"; }

    @Override
    public void onEvent(AgentEvent event) {
        runtime.find(event.sessionId()).ifPresent(session -> {
            if (session.getState().isActive()) {
                enqueueStep(session);
            }
        });
    }
}
```

**为什么把"触发"和"执行"分离？** 这是调度系统的经典解耦：调度器只决定"何时该做"（入队），执行器（Handler）只关心"怎么做"。二者通过队列解耦，可以独立扩缩容、独立限流、独立重试。

### 4.3 端到端数据流全景

把上面的零件串起来，一条完整会话的生命周期如下：

```
  [每天 09:00]                        [手动触发 POST /trending/trigger]
       |                                       |
       v                                          v
  TrendingScheduler.triggerDaily()      TrendingScheduler.triggerOnce()
       |                                          |
       +--------------------+---------------------+
                            |
                            v
              runtime.createSession("github-trending")   状态: CREATED，落库
              runtime.start(session)                     状态: CREATED -> RUNNING
              enqueueStep(session)                       入队 step=0 任务
                            |
                            v
      +-----------------------------------------------------------+
      |          AgentScheduler.heartbeat() 每 500ms 拉取          |
      +-----------------------------------------------------------+
                            |
                            v  poll 到任务，分发给
              GithubTrendingHandler.handle(task)
                            |
     +----------------------+-----------------------+
     |  step=0 LOGIN     -> stub.login()            |
     |  advanceAndCheckpoint(session)  stepIndex=1  |  打点：崩溃最多丢一步
     |  publish TASK_SUCCESS  -> MonitorEventListener 记指标
     |  publish NEXT_STEP_READY                     |
     +----------------------+-----------------------+
                            |
                            v  TrendingScheduler.onEvent() 续投
              enqueueStep(session)  入队 step=1 任务
                            |
                            v  （FETCH -> SUMMARIZE -> NOTIFY 同样循环）
                            |
                            v  step=4 越界
              runtime.complete(session)   状态: RUNNING -> COMPLETED（终态）
              publish SESSION_COMPLETED   -> MonitorEventListener: RUNNING_SESSIONS -1
```

这条链路的美妙之处在于：**每个环节都是无状态的、可独立重放的**。Handler 不持有任何跨步骤的内存状态——所有进度都在 `Context` 里、都已落库。这正是下面崩溃恢复能成立的根本前提。

---

## 第五部分 · 深入理解：崩溃恢复与重试的完整演示

### 5.1 崩溃恢复：从"第几步"续跑

假设 Agent 在执行 `SUMMARIZE`（step=2）时进程被 kill。此刻数据库里的 session 是什么状态？

因为我们在**每一步完成后**才 `advanceAndCheckpoint`，而崩溃发生在 SUMMARIZE 执行**过程中**（还没打点），所以库里记录的是 `stepIndex=2, state=RUNNING`（上一步 FETCH 完成时打的点）。

进程重启后，`RecoveryService.recoverAll(...)` 扫描所有非终态会话：

```
  重启 -> RecoveryService.recoverAll(session -> enqueueStep(session))
              |
              v  扫到 stepIndex=2, state=RUNNING 的会话
              +-> 重新入队 step=2 任务
                     |
                     v  Handler 从 SUMMARIZE 重新执行
                        （FETCH 抓到的 repos 还在 Context 里，无需重抓）
```

**关键洞察**：因为 FETCH 的产物 `repos` 已经 `put` 进 Context 并落库，恢复后 SUMMARIZE 可以直接用，不必重新登录、重新抓取。这就是检查点的价值——**恢复的粒度是"步"，不是"整个流程"**。

这里也体现了"至少一次"语义：SUMMARIZE 可能被执行两次（崩溃前一次、恢复后一次）。因此每一步的操作最好是**幂等**的，或者能容忍重复。这是设计长流程步骤时必须考虑的约束。

### 5.2 重试与降级：LOGIN 失败会怎样

`TrendingStubClient` 预留了 `setFailLogin(true)` 用于演示登录失败。当 LOGIN 抛异常时：

```
  Handler.handle() 抛异常
       |
       v  TaskDispatcher 捕获，查 RetryPolicy.canRetry(attempts)
       |
   +---+------------------ 还能重试 -------------------+
   |                                                   |
   v                                                   v
 requeue（延迟 nextDelayMillis 后再试）          重试耗尽
 publish TASK_RETRIED                            |
 （指数退避：500ms -> 1s -> 2s ...）              v
                                          deadLetterQueue.put(task, reason)
                                          publish TASK_DEAD
                                          （DLQ size > 0 -> 告警）
```

**为什么用指数退避而不是固定间隔？** 如果 GitHub 正在限流，固定间隔的密集重试只会加剧限流；指数退避（`500ms -> 1s -> 2s -> 4s`）给下游留出恢复窗口，是对外部系统的"礼貌"。而重试彻底耗尽后进死信队列，则是承认"这个任务当前无法自动完成"，交给人工——**永远不要让一个毒丸任务无限重试拖垮整个队列**。

### 5.3 全链路可观测

上述每一个事件（`TASK_SUCCESS` / `TASK_RETRIED` / `TASK_DEAD` / `SESSION_COMPLETED` ...）都会被 `MonitorEventListener` 通配捕获并翻译成指标。所以运维时只需看一眼仪表盘：

```
  GET /api/agent/metrics
  {
    "task.success": 128,        <- 累计完成的步数
    "task.retried": 3,          <- 发生过重试（可能限流）
    "task.dead": 0,             <- 死信为 0，健康
    "session.completed": 32,    <- 32 条会话善终
    "gauge.running.sessions": 1 <- 当前 1 条在跑
  }
```

---

## 第六部分 · 让机器"看得见、点得动"：REST 运维门面

最后，`AgentApiController` 用最薄的一层 HTTP 把这台机器的运维能力暴露出来：

```
  POST /api/agent/trending/trigger   手动触发一次流水线（无需等到 9 点）
  GET  /api/agent/sessions/{id}      查询会话跑到哪一步、什么状态、有无报错
  GET  /api/agent/metrics            实时指标仪表盘
  GET  /api/agent/dlq                查看死信队列（毒丸任务）
  GET  /api/agent/queue/size         队列积压深度
  POST /api/agent/scheduler/pause    暂停消费（优雅停机 / 故障隔离演练）
  POST /api/agent/scheduler/resume   恢复消费
```

设计原则是 **Controller 极薄**：只做参数接收与视图组装，所有业务逻辑都委托给已装配好的领域组件。注意 `toView` 方法把领域对象转成 DTO Map 再返回——**绝不直接把 `AgentSession` 序列化给前端**，避免暴露内部结构、也避免序列化 `Context` 里那些不该外泄的中间产物。

一个典型的联调流程：

```
  1) POST /api/agent/trending/trigger        -> 拿到 sessionId
  2) GET  /api/agent/sessions/{sessionId}    -> 观察 stepIndex 从 0 递增到 4
  3) GET  /api/agent/metrics                 -> 看 task.success 累加
  4) 若某步失败，GET /api/agent/dlq          -> 确认是否进死信
```

---

## 第七部分 · 面试高频考点

**Q1：为什么要把状态变更收敛进 `transitionTo` 一个方法，而不是让各处直接 `setState`？**
A：一致性不能靠自觉。状态变更必须伴随"校验→落库→广播"三个动作，任何一处遗漏都会导致崩溃恢复时状态不一致或指标失真。收敛进单一入口，用架构约束代替口头约定，杜绝后门。

**Q2："单步驱动 + 事件续投"相比"一个方法顺序执行四步"有什么优势？**
A：①可检查点——每步完成即打点，崩溃最多丢一步；②可恢复——从任意步续跑，无需从头；③可重试/限流——单独某步失败只重试该步；④解耦——调度器决定"何时做"，执行器决定"怎么做"，可独立扩缩容。

**Q3：进程在执行第 3 步时崩溃，重启后从第几步恢复？中间产物会丢吗？**
A：因为打点发生在每步完成后，库里记录的是上一步（第 2 步）完成时的检查点，恢复后从第 3 步重新执行。第 1、2 步的产物已 `put` 进 Context 并落库，恢复后可直接复用，无需重做。但第 3 步可能被执行两次（至少一次语义），故步骤最好幂等。

**Q4：`@EnableScheduling` 忘了加会发生什么？为什么这类 bug 难排查？**
A：所有 `@Scheduled` 方法都不会执行——消费心跳不跳、每日触发不触发。机器"看起来启动成功"但永远不干活，且没有任何报错日志，只能靠"任务永远不被消费"这个现象反推，极难定位。

**Q5：为什么 `AgentStateMachine` 和 `RetryPolicy` 用 `@Bean` 注册而不是 `@Component`？**
A：它们是纯领域对象（POJO），不应被 Spring 注解污染。用 `@Bean` 在 Config 里注册，既能享受依赖注入，又能在单元测试里直接 `new` 出来独立测试，保持领域纯粹性。

---

## 本章小结

本章完成了从"一堆零件"到"一台机器"的总装：

- **AgentRuntime** 作为总控，用 `transitionTo` 的五步一致动作序列强制状态一致性，是全系统唯一合法的状态变更入口。
- **Day12RuntimeConfig** 完成装配：POJO 用 `@Bean` 注册，`@EnableScheduling` 点亮所有心跳，`EventBus` 自动收集监听器。
- **单步驱动 + 事件续投** 把长流程拆成可检查点、可恢复、可重试的小步；**双引擎**（cron 定时 + 事件续投）驱动流程自动推进。
- **GitHub Trending Agent** 端到端跑通全链路，并通过桩隔离外部依赖，让核心流程纯确定性、可测试。
- **崩溃恢复**从检查点续跑、**重试降级**用指数退避 + 死信兜底、**全链路可观测**由通配监听器无侵入埋点。
- **AgentApiController** 用极薄的 REST 门面让机器"看得见、点得动"。

至此，一台真正意义上"永不停歇、崩溃可恢复、失败可观测、运维可操控"的长生命周期 Agent 全部完成。它不再是一个跑完就退出的脚本，而是一个能在生产环境持续运转数月的**有状态服务**。这，就是 Long Running Agent 的全部要义。