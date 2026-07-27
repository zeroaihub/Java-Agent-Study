# 第五章：Scheduler 调度器——依赖、优先级与 DAG 就绪判定

> 本章实现 `Scheduler`：把静态的 Plan（一堆带依赖和优先级的步骤）变成有序的执行流。核心是「DAG 就绪判定 + 优先级排序 + 环检测」。它是连接 Plan 与 Executor 的交通指挥。

---

## 第一部分：为什么学（核心价值）

### 1. 为什么不能「按列表顺序」直接执行？

新手最容易犯的错：`for (step : plan.steps()) execute(step)`。这在「无依赖的线性计划」下能跑，但一遇到真实场景就崩：

- LLM 拆出的步骤顺序不一定等于执行顺序（它可能先写 step-3 再写 step-1）。
- 步骤间有依赖（step-2 依赖 step-1），必须等前置完成才能跑。
- 重规划后插入的新步骤，破坏了原列表顺序。

**正确做法**：不看列表顺序，只看「依赖是否满足」。谁的依赖都 DONE 了，谁就能跑——这就是 DAG（有向无环图）调度。

### 2. 为什么需要「优先级」？

当多个步骤同时就绪（依赖都满足），先跑谁？如果没有优先级，就只能随机或按 id——但业务上有轻重缓急（风控里「先冻结账户」比「记日志」重要）。优先级让「就绪集合」内部有确定的、符合业务意图的排序。

### 3. 为什么要「环检测」？

LLM 偶尔会拆出**循环依赖**（step-1 依赖 step-2，step-2 又依赖 step-1）。这会让调度器永远找不到「依赖全满足」的步骤，陷入死锁。生产级调度器**必须**能检测出环并 fail-fast，而不是默默卡死。

---

## 第二部分：是什么（Scheduler 逐步实现）

### 2.1 接口定义

```java
package com.zero.ai.agentstudy.day10planningagent.engine;

import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;

import java.util.List;
import java.util.Optional;

/** 调度器：按依赖 + 优先级决定下一个执行哪一步。 */
public interface Scheduler {
    /** 返回下一个应执行的步骤；无就绪步骤返回 empty。 */
    Optional<PlanStep> nextStep(Plan plan, PlanningContext ctx);

    /** 返回当前所有就绪步骤（为将来并行调度预留）。 */
    List<PlanStep> readySteps(Plan plan);

    /** 是否全部步骤已终结（DONE/SKIPPED）。 */
    boolean isAllDone(Plan plan);

    /** 校验计划无循环依赖，有环则抛异常。 */
    void validateNoCycle(Plan plan);
}
```

### 2.2 就绪判定：什么叫「一个步骤就绪」？

```
一个步骤就绪 ⇔ 它自己是 PENDING  且  它 dependsOn 的所有步骤都是 DONE
```

ASCII 演示（step-3 依赖 step-1、step-2）：

```
 step-1: DONE ✓
 step-2: DONE ✓   →  step-3 的依赖全满足  →  step-3 就绪 ✓
 step-3: PENDING

 若 step-2 还是 RUNNING：
 step-1: DONE ✓
 step-2: RUNNING ✗ →  step-3 依赖未满足   →  step-3 不就绪
 step-3: PENDING
```

### 2.3 DagScheduler 实现

```java
package com.zero.ai.agentstudy.day10planningagent.engine;

import com.zero.ai.agentstudy.day10planningagent.core.*;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DagScheduler implements Scheduler {

    @Override
    public Optional<PlanStep> nextStep(Plan plan, PlanningContext ctx) {
        // 就绪集合内按优先级从高到低选，优先级相同按 id 稳定排序
        return readySteps(plan).stream()
                .max(Comparator
                        .comparingInt((PlanStep s) -> s.priority().weight())
                        .thenComparing(s -> s.id(), Comparator.reverseOrder()));
    }

    @Override
    public List<PlanStep> readySteps(Plan plan) {
        Set<String> doneIds = new HashSet<>();
        for (PlanStep s : plan.steps()) {
            if (s.status() == StepStatus.DONE || s.status() == StepStatus.SKIPPED) {
                doneIds.add(s.id());
            }
        }
        List<PlanStep> ready = new ArrayList<>();
        for (PlanStep s : plan.steps()) {
            if (s.status() != StepStatus.PENDING) continue;      // 只挑待执行的
            if (doneIds.containsAll(s.dependsOn())) {            // 依赖全满足
                ready.add(s);
            }
        }
        return ready;
    }

    @Override
    public boolean isAllDone(Plan plan) {
        return plan.steps().stream().allMatch(s ->
                s.status() == StepStatus.DONE || s.status() == StepStatus.SKIPPED);
    }

    /** 用 DFS 三色标记法检测循环依赖。 */
    @Override
    public void validateNoCycle(Plan plan) {
        Map<String, PlanStep> byId = new HashMap<>();
        for (PlanStep s : plan.steps()) byId.put(s.id(), s);

        Map<String, Integer> color = new HashMap<>(); // 0=白未访问,1=灰在栈中,2=黑已完成
        for (PlanStep s : plan.steps()) {
            if (color.getOrDefault(s.id(), 0) == 0) {
                dfs(s.id(), byId, color);
            }
        }
    }

    private void dfs(String id, Map<String, PlanStep> byId, Map<String, Integer> color) {
        color.put(id, 1); // 灰
        PlanStep step = byId.get(id);
        if (step != null) {
            for (String dep : step.dependsOn()) {
                int c = color.getOrDefault(dep, 0);
                if (c == 1) {
                    throw new IllegalStateException("检测到循环依赖，涉及步骤：" + id + " -> " + dep);
                }
                if (c == 0 && byId.containsKey(dep)) {
                    dfs(dep, byId, color);
                }
            }
        }
        color.put(id, 2); // 黑
    }
}
```

**代码讲解要点**：
- `readySteps`：先收集所有已终结步骤的 id，再筛出「PENDING 且依赖全在已终结集合里」的步骤。这就是 DAG 就绪判定的本质。
- `nextStep`：在就绪集合里用 `max` + 优先级权重比较选出最高优先级；`thenComparing` 保证优先级相同时结果稳定（可复现），便于测试。
- `validateNoCycle`：经典 **DFS 三色标记法**——遇到「灰色（正在递归栈中）」的节点说明存在回边，即有环，立即 fail-fast。这是图论里检测有向图环的标准算法。

### 2.4 为什么把「就绪判定」和「选一个」分成两个方法

`readySteps` 返回**全部**就绪步骤，`nextStep` 从中**选一个**。这样拆分的好处：将来支持并行时，`readySteps` 一次返回多个，交给线程池并行执行；串行时 `nextStep` 只取一个。**接口预留并行能力，实现当前保持串行**——这是「面向未来设计，但不过度设计」的平衡。

---

## 第三部分：怎么用（跑通调度 + 演示环检测）

### 3.1 构造一个带依赖的计划并调度

```java
List<PlanStep> steps = List.of(
    new PlanStep("step-1", "浏览页面", List.of(), Priority.HIGH, "browser"),
    new PlanStep("step-2", "提取数据", List.of("step-1"), Priority.HIGH, "extract"),
    new PlanStep("step-3", "写日志",  List.of(), Priority.LOW, "log"),
    new PlanStep("step-4", "总结",    List.of("step-2"), Priority.MEDIUM, "summary")
);
Plan plan = new Plan("plan-1", "goal-1", new ArrayList<>(steps));
DagScheduler scheduler = new DagScheduler();
scheduler.validateNoCycle(plan);   // 无环，通过

// 第 1 轮：就绪的是 step-1(HIGH) 和 step-3(LOW)（都无依赖）
//         nextStep 选优先级最高 → step-1
PlanStep s1 = scheduler.nextStep(plan, ctx).get();  // step-1
s1.markDone("页面HTML");

// 第 2 轮：step-2 依赖 step-1 已满足 → 就绪；step-3 也就绪
//         就绪集合 {step-2(HIGH), step-3(LOW)} → 选 step-2
PlanStep s2 = scheduler.nextStep(plan, ctx).get();  // step-2
s2.markDone("提取结果");

// 第 3 轮：step-4 依赖 step-2 满足 →就绪；step-3 就绪
//         {step-4(MEDIUM), step-3(LOW)} → 选 step-4
// 第 4 轮：只剩 step-3 → 选 step-3
// isAllDone → true
```

**观察**：低优先级的 step-3 虽然一开始就就绪，却总被高优先级步骤「插队」，直到最后才执行——这正是优先级调度的效果。

### 3.2 演示环检测拦截

```java
List<PlanStep> bad = List.of(
    new PlanStep("A", "任务A", List.of("B"), Priority.HIGH, null),
    new PlanStep("B", "任务B", List.of("A"), Priority.HIGH, null)  // A↔B 互相依赖
);
Plan cyclic = new Plan("plan-x", "goal-x", new ArrayList<>(bad));
scheduler.validateNoCycle(cyclic);
// → 抛 IllegalStateException: 检测到循环依赖，涉及步骤：A -> B
```

**价值**：LLM 拆出病态计划时，在执行前就被拦下（主循环会在 PLANNING→READY 前调 validateNoCycle，失败则转 FAILED 或触发重规划），而不是运行时死锁。

### 3.3 主循环里怎么用（预览）

```java
scheduler.validateNoCycle(plan);
ctx.transitionTo(PlanState.READY);
while (!scheduler.isAllDone(plan)) {
    ctx.guardBudgetOrThrow();
    Optional<PlanStep> next = scheduler.nextStep(plan, ctx);
    if (next.isEmpty()) {
        // 没有就绪步骤但又没全 DONE → 说明有步骤 FAILED 卡住依赖链
        break; // 交给反思/重规划处理
    }
    // ... 执行 next.get() ...
}
```

**注意那个 `next.isEmpty()` 分支**：如果「没全做完，却也没就绪步骤」，说明有步骤 FAILED 导致其后继永远无法就绪——这是需要反思/重规划介入的信号，不能当成功处理。

---

## 第四部分：用在哪（调度器的真实应用）

| 场景 | 调度器承担的职责 |
|------|-----------------|
| 数据管道 | 抽取→清洗→计算有依赖，多个无依赖抽取可并行 |
| CI/CD | build→test→deploy 依赖链，lint 与 test 可并行 |
| 微服务编排 | 「下单→扣库存→支付→发货」严格依赖，通知类可异步 |
| Agent 任务 | 本项目：浏览→提取→总结→排版的依赖调度 |
| 大数据 | Spark/Airflow DAG 调度，本质同款算法 |

**关键认知**：你写的 `DagScheduler`，和 Airflow、Spark DAGScheduler、Maven 的模块依赖解析，用的是**同一套图论算法**（拓扑就绪 + 环检测）。掌握它，你就掌握了所有「依赖调度」系统的通用内核。

---

## 第五部分：避坑指南

1. **别按列表顺序执行步骤**。必须按依赖就绪判定。列表顺序 ≠ 执行顺序，尤其重规划后。

2. **别忘了环检测**。LLM 会拆出循环依赖，不检测就死锁。执行前必须 `validateNoCycle`。

3. **别把 FAILED 步骤当 DONE处理依赖**。就绪判定里，依赖必须是 DONE/SKIPPED，FAILED 不算满足——否则会在错误结果上继续执行。

4. **别忽略「无就绪步骤但未全完成」的情况**。这不是成功，是卡死信号，要交给反思/重规划。

5. **别让优先级排序不稳定**。相同优先级要有确定的次级排序（如按 id），否则测试结果飘忽、难复现。

6. **别在 Scheduler 里执行步骤**。Scheduler 只「选」，不「做」。选出来交给 Executor 执行——职责分离。

7. **并行调度别忽视共享写冲突**。`readySteps` 返回多个并行执行时，它们同时写 Context/黑板，需保证线程安全（chapter-08）。

---

## 本章小结

我们实现了 `DagScheduler`：用「依赖全 DONE 才就绪」做拓扑调度，用优先级权重在就绪集合内排序，用 DFS 三色标记检测循环依赖并 fail-fast。接口层用 `readySteps`（返回全部就绪）为并行预留，`nextStep`（选一个）满足当前串行。

**核心记忆**：不看顺序看依赖；就绪集合按优先级；执行前必检环；Scheduler 只选不做。

**下一章预告**：chapter-06 实现 `Executor` 与工具体系——`ToolRegistry`/`ToolSpec`/`ToolSelector`，让选出的步骤真正调用工具执行，并加上重试策略。

---

## FAQ

**Q1：为什么不用现成的拓扑排序一次性排好序？**
因为计划是**动态**的——重规划会插入新步骤、有步骤会失败。一次性拓扑排序假设图不变；而 Agent 的图在运行中会变，所以我们每轮「即时就绪判定」，更灵活。

**Q2：优先级和依赖冲突了怎么办？**
依赖是硬约束（必须满足），优先级是软排序（就绪后才比较）。永远先满足依赖，再在就绪者之间比优先级。二者不在同一层，不会真正「冲突」。

**Q3：如何支持并行执行？**
`readySteps` 一次返回多个就绪步骤，提交给线程池并行执行，执行完统一写回 Context（需线程安全）。chapter-08 展开。

---

## 面试高频题

1. 如何判断一个 DAG 中的步骤是否就绪？
2. DFS 三色标记法如何检测有向图的环？
3. 依赖（硬约束）和优先级（软排序）的关系是什么？
4. 为什么调度要「每轮即时判定」而非「一次性拓扑排序」？
5. 出现「无就绪步骤但未全完成」意味着什么？该如何处理？

---

## 扩展阅读

- 算法导论——拓扑排序、DFS 环检测（三色标记）
- Apache Airflow DAGScheduler 源码
- Spark 的 Stage/Task DAG 调度
- Maven Reactor 的模块依赖解析