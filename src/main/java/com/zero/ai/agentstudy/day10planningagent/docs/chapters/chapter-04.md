# 第四章：PlanningContext 黑板与 PlanState 状态机

> 本章实现 Planning Agent 的「中枢神经」：`PlanState`（带转移校验的状态机枚举）与 `PlanningContext`（黑板模式的共享上下文）。它俩合起来，就是承载「计划 + 当前状态 + 观察历史 + 预算计数」的运行时容器，是主循环得以运转的地基。

---

## 第一部分：为什么学（核心价值）

### 1. 为什么需要一个「黑板」？

第二章讲过：Planner、Scheduler、Executor、Reflector 四大组件不能互相直接调用，否则耦合成一团、无法单测。那它们怎么协作？答案是**黑板模式（Blackboard Pattern）**——设一块公共「黑板」，各组件从黑板读所需数据、把产出写回黑板，彼此互不认识。

```
        ┌──────────── PlanningContext（黑板）────────────┐
        │  goal / plan / state / observations / budget    │
        └───▲────────▲────────▲────────▲──────────────────┘
            │        │        │        │
       [Planner] [Scheduler][Executor][Reflector]   ← 都只和黑板打交道
```

**类比**：一群专家围着一块黑板破案——法医写尸检结果、痕检写指纹、刑警读这些线索推理。专家之间不用互相打电话，黑板就是他们的协作介质。这正是复杂 Agent 解耦的经典范式。

### 2. 为什么状态机要「带校验」？

第二章强调过：状态非法跳转是最隐蔽的 bug。本章我们要把「合法转移表」真正编码进 `PlanState`，让任何非法转移在运行期**立即抛异常**，而不是悄悄产生错误状态、在几百行之后才崩溃。这就是「fail-fast（快速失败）」原则——错误越早暴露，定位越便宜。

### 3. 为什么这是「可恢复、可观测」的前提？

黑板把所有运行时状态集中在一个对象里。这意味着：把 `PlanningContext` 序列化落库，就能实现「宕机恢复」；把它的观察历史打印出来，就能实现「全链路追溯」。**状态集中，是可恢复和可观测的物理前提。**

---

## 第二部分：是什么（逐类实现）

### 2.1 PlanState：带转移校验的状态机

```java
package com.zero.ai.agentstudy.day10planningagent.context;

import java.util.Set;
import java.util.EnumMap;
import java.util.Map;

/**
 * 计划状态机。每个状态显式声明它「允许转移到哪些状态」，
 * 任何未声明的转移都是非法的，会在运行期抛异常（fail-fast）。
 */
public enum PlanState {
    NEW,
    PLANNING,
    READY,
    EXECUTING,
    REFLECTING,
    RE_PLANNING,
    WAITING_HUMAN,
    SUCCEEDED,   // 终态
    FAILED;      // 终态

    // 合法转移表：key 可转移到 value 集合中的任意状态
    private static final Map<PlanState, Set<PlanState>> ALLOWED = new EnumMap<>(PlanState.class);
    static {
        ALLOWED.put(NEW,           Set.of(PLANNING, FAILED));
        ALLOWED.put(PLANNING,      Set.of(READY, FAILED));
        ALLOWED.put(READY,         Set.of(EXECUTING, FAILED));
        ALLOWED.put(EXECUTING,     Set.of(REFLECTING, FAILED));
        ALLOWED.put(REFLECTING,    Set.of(EXECUTING, RE_PLANNING, WAITING_HUMAN, SUCCEEDED, FAILED));
        ALLOWED.put(RE_PLANNING,   Set.of(READY, FAILED));
        ALLOWED.put(WAITING_HUMAN, Set.of(EXECUTING, FAILED));
        ALLOWED.put(SUCCEEDED,     Set.of());   // 终态，不可再转移
        ALLOWED.put(FAILED,        Set.of());   // 终态，不可再转移
    }

    /** 判断能否转移到目标状态。 */
    public boolean canTransitionTo(PlanState target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** 是否终态。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
```

**为什么用 EnumMap + 静态转移表**：EnumMap 针对枚举 key 做了数组级优化，查表 O(1)；把「合法转移」声明成一张显式表，让规则一目了然、可评审、可测试。将来要加新状态，只改这张表即可。

### 2.2 Observation：观察记录（ReAct 三元组的持久化）

```java
package com.zero.ai.agentstudy.day10planningagent.context;

import java.time.Instant;

/** 一次执行的观察记录：把 Reasoning/Action/Observation 三元组落库，用于追溯。 */
public record Observation(
        String stepId,
        String thought,      // Reasoning：为什么做这步
        String action,       // Action：调了什么工具、什么参数
        String observation,  // Observation：得到什么结果
        boolean ok,
        Instant at
) {
    public static Observation of(String stepId, String thought, String action,
                                 String observation, boolean ok) {
        return new Observation(stepId, thought, action, observation, ok, Instant.now());
    }
}
```

### 2.3 PlanningContext：黑板本体

这是本章核心。它集中管理：目标、当前计划、当前状态、观察历史、预算计数、最终结果。并且**所有状态转移都必须经过它的 `transitionTo()`**（内部调用 PlanState 校验）。

```java
package com.zero.ai.agentstudy.day10planningagent.context;

import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 黑板：Planning 运行时的共享上下文。所有组件通过它读写共享状态，彼此不直接耦合。
 */
public class PlanningContext {

    private final Goal goal;
    private Plan plan;
    private PlanState state = PlanState.NEW;

    private final List<Observation> observations = new ArrayList<>();
    private final Map<String, Object> blackboard = new ConcurrentHashMap<>(); // 自由键值区

    // ---- 预算计数 ----
    private int stepCount = 0;      // 已执行步数
    private int replanCount = 0;    // 已重规划次数
    private final long startedAt = System.currentTimeMillis();

    private String finalResult;     // 最终产出（如 Markdown）
    private String failReason;      // 失败原因

    public PlanningContext(Goal goal) {
        this.goal = goal;
    }

    // ---- 状态转移（唯一入口，带校验）----
    public void transitionTo(PlanState target) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateException(
                "非法状态转移：" + state + " -> " + target);
        }
        this.state = target;
    }

    // ---- 观察记录 ----
    public void record(PlanStep step, StepResult result, String thought, String action) {
        this.stepCount++;
        observations.add(Observation.of(
                step.id(), thought, action,
                result.ok() ? result.output() : result.error(),
                result.ok()));
    }

    public void incReplan() { this.replanCount++; }

    // ---- 预算护栏：超限抛异常，由主循环捕获后转 FAILED ----
    public void guardBudgetOrThrow() {
        if (stepCount >= goal.maxSteps())
            throw new BudgetExceededException("超过最大步数 " + goal.maxSteps());
        if (replanCount > goal.maxReplan())
            throw new BudgetExceededException("超过最大重规划次数 " + goal.maxReplan());
        if (System.currentTimeMillis() - startedAt > goal.timeoutMs())
            throw new BudgetExceededException("超过整体超时 " + goal.timeoutMs() + "ms");
    }

    // ---- 自由黑板区（组件间传临时数据）----
    public void put(String key, Object value) { blackboard.put(key, value); }
    public Object get(String key) { return blackboard.get(key); }

    // ---- getters/setters ----
    public Goal goal() { return goal; }
    public Plan plan() { return plan; }
    public void setPlan(Plan plan) { this.plan = plan; }
    public PlanState state() { return state; }
    public List<Observation> observations() { return observations; }
    public int stepCount() { return stepCount; }
    public int replanCount() { return replanCount; }
    public String finalResult() { return finalResult; }
    public void setFinalResult(String r) { this.finalResult = r; }
    public String failReason() { return failReason; }
    public void setFailReason(String r) { this.failReason = r; }

    /** 拼接已完成步骤的成果，供重规划/总结时作为上下文。 */
    public String completedSummary() {
        StringBuilder sb = new StringBuilder();
        if (plan != null) {
            for (PlanStep s : plan.steps()) {
                if (s.isDone()) sb.append("[").append(s.id()).append("] ")
                        .append(s.description()).append(" => ").append(s.result()).append("\n");
            }
        }
        return sb.toString();
    }
}
```

```java
package com.zero.ai.agentstudy.day10planningagent.context;

/** 预算超限异常：步数/重规划/超时任一超限时抛出。 */
public class BudgetExceededException extends RuntimeException {
    public BudgetExceededException(String message) { super(message); }
}
```

**为什么用 ConcurrentHashMap 做自由黑板区**：为将来「并行执行多个步骤」预留线程安全能力；即便当前串行，也无额外成本。`observations` 目前串行写，暂用 ArrayList，并行化时再换并发集合（chapter-08 讨论）。

---

## 第三部分：怎么用（把状态机跑起来 + 演示非法转移被拦截）

### 3.1 正常流转演示

```java
Goal goal = Goal.of("演示状态机");
PlanningContext ctx = new PlanningContext(goal);

System.out.println(ctx.state());         // NEW
ctx.transitionTo(PlanState.PLANNING);    // OK
ctx.transitionTo(PlanState.READY);       // OK
ctx.transitionTo(PlanState.EXECUTING);   // OK
ctx.transitionTo(PlanState.REFLECTING);  // OK
ctx.transitionTo(PlanState.SUCCEEDED);   // OK（终态）
```

### 3.2 非法转移被 fail-fast 拦截

```java
PlanningContext ctx = new PlanningContext(goal);
ctx.transitionTo(PlanState.PLANNING);
// 直接跳到 SUCCEEDED？非法！PLANNING 只能到 READY/FAILED
ctx.transitionTo(PlanState.SUCCEEDED);
// → 抛 IllegalStateException: 非法状态转移：PLANNING -> SUCCEEDED
```

**价值**：在开发/测试阶段，任何写错的状态跳转会立刻炸出来并告诉你「从哪到哪非法」，而不是留到生产环境产生诡异行为。

### 3.3 预算护栏演示

```java
Goal tiny = new Goal("g1", "小预算任务", 2, 1, 60_000L); // 只允许 2 步
PlanningContext ctx = new PlanningContext(tiny);
// 主循环里每轮先 ctx.guardBudgetOrThrow();
// 执行到第 3 步时 stepCount>=2，抛 BudgetExceededException
// 主循环 catch 后 ctx.transitionTo(FAILED) 并记录原因
```

### 3.4 演进 Planner 接口以使用 Context

第三章 Planner 接口用的是 `plan(goal)`。现在有了 Context，我们可以（在第七章正式）把重规划改造成携带上下文：

```java
// 演进后的重规划签名（第七章 PlanningService 会用到）
Plan replan(Goal goal, PlanningContext ctx);
// LlmPlanner.replan 内部用 ctx.completedSummary() 拿已完成成果做增量重规划
```

---

## 第四部分：用在哪（黑板与状态机的真实威力）

| 能力 | 靠什么实现 | 真实收益 |
|------|-----------|---------|
| 宕机恢复 | 序列化 PlanningContext 落库 | 任务跑到一半机器挂了，重启从断点继续 |
| 全链路追溯 | observations 列表 | 线上出错，完整回放 Agent 每一步的想/做/看 |
| 组件解耦 | 黑板模式 | 四大组件独立开发、独立测试、可替换 |
| 防非法状态 | PlanState 校验 | 杜绝「从成功态又跳回执行」这类诡异 bug |
| 成本可控 | budget 计数 + guard | 防止 LLM 无限循环烧钱 |
| 并行扩展 | ConcurrentHashMap 黑板 | 将来并行执行步骤无需重构数据层 |

**企业落地**：Temporal、AWS Step Functions 的本质，都是「把工作流状态集中持久化 + 状态转移受控」。你在本章手写的这套，就是它们的极简内核——理解了它，你就理解了工业级工作流引擎的第一性原理。

---

## 第五部分：避坑指南

1. **别提供裸的 `setState()`**。状态只能通过 `transitionTo()` 改，否则校验形同虚设。本课程 Context 里根本没有 setState。

2. **别把预算 guard 放在循环体尾部**。必须放在**每轮开头**先检查再执行，否则会「先超支执行完这步」才发现超限。

3. **别把 observations 只打日志不进 Context**。日志会滚动丢失，Context 才是可持久化、可恢复的唯一真相源。

4. **别在黑板里存超大对象**（如整页 HTML 原文）。黑板要能序列化落库，塞大对象会撑爆存储。大内容应存对象存储/DB，黑板里只放引用/摘要。

5. **别忘了终态不可转移**。SUCCEEDED/FAILED 的允许集合是空集，任何从终态出发的转移都该被拒。

6. **别让 Context 承担业务逻辑**。它是「数据容器 + 状态守卫」，不该写「怎么规划、怎么执行」的逻辑——那是各组件的职责。Context 胖了就成了上帝对象。

7. **并行化前别用 ArrayList 存 observations**。当前串行安全；一旦并行执行步骤，需换 `CopyOnWriteArrayList` 或加锁。

---

## 本章小结

我们实现了 Planning Agent 的中枢：`PlanState`用显式转移表 + `canTransitionTo` 实现 fail-fast 状态机；`PlanningContext` 用黑板模式集中管理目标、计划、状态、观察、预算，并通过 `transitionTo`/`guardBudgetOrThrow` 守住「状态合法性」和「成本上限」两条底线。

**核心记忆**：状态集中 → 可恢复可观测；转移受控 → 无非法跳转；预算前置 → 成本可控；黑板协作 → 组件解耦。

**下一章预告**：chapter-05 实现 `Scheduler`——基于依赖（DAG 就绪判定）+ 优先级，决定「下一个执行哪一步」，把静态 Plan 变成有序的执行流。

---

## FAQ

**Q1：Context 要不要做成 Spring Bean？**
不要做成单例 Bean。每个 Planning 任务应 `new` 一个独立 Context（它承载单次任务的状态）。它是「请求作用域的运行时对象」，不是共享服务。

**Q2：如何实现宕机恢复？**
把 Context（含 plan、state、observations、计数）用 JSON/JPA 持久化到 DB。重启时反序列化，主循环从当前 state 继续。本课程主线不落库（内存态），持久化作为 chapter-08 扩展点。

**Q3：observations 会不会无限增长？**
长任务会。生产上可做「滚动窗口 + 摘要压缩」：保留最近 N 条原文 + 更早的压缩摘要。这也是省 Token 的手段。

---

## 面试高频题

1. 什么是黑板模式？它如何解耦多组件协作？
2. 为什么状态机要用「显式转移表 + fail-fast」而非 String 字段？
3. 预算护栏为什么必须放在循环开头？放尾部有什么问题？
4. PlanningContext 为什么不应做成单例 Bean？
5. 如何基于 Context 实现宕机恢复与全链路追溯？

---

## 扩展阅读

- Blackboard Pattern（POSA 架构模式卷一）
- Temporal / AWS Step Functions 文档——工业级状态持久化
- 《Release It!》——稳定性模式、fail-fast
- Event Sourcing 模式——observations 本质是事件流