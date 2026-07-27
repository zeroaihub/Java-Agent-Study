# 第七章：Reflection 反思、Re-Planning 重规划与 PlanningService 主循环

> 这是全篇的高潮。前六章造好了「大脑（Planner）、指挥（Scheduler）、双手（Executor）」，本章加上「自省能力（Reflection）」，并用 `PlanningService` 主循环把它们串成能**自我纠错、动态重规划**的完整闭环，最后暴露 REST 入口。

---

## 第一部分：为什么学（核心价值）

### 1. 没有反思的 Agent 是「瞎子」

前六章的执行链是单向的：拆解→调度→执行→结束。但真实任务会出错、会偏航。没有反思，Agent 只会「一条道走到黑」——步骤失败了不知道换策略，结果偏了不知道纠正。**Reflection 让 Agent 具备「回看-判断-调整」的元认知能力**，这是 Agent 与普通脚本的本质区别。

### 2. 四种裁决——反思到底在决定什么？

每执行完一步（或一步失败后），反思器要回答：「接下来怎么办？」它给出四选一的裁决：

| 裁决 | 含义 | 触发场景 |
|------|------|---------|
| `CONTINUE` | 继续执行下一步 | 本步成功，计划仍有效 |
| `RETRY_STEP` | 重试当前步骤 | 瞬时失败，值得再试（Executor 内重试之外的高层重试） |
| `REPLAN` | 重新规划剩余步骤 | 方向错了/出现新信息/多步失败，需改计划 |
| `ABORT` | 放弃任务 | 不可恢复错误 / 预算耗尽 / 目标无法达成 |

### 3. Re-Planning 是「增量修正」不是「从零重来」

新手误区：重规划 = 把 Plan 扔了重新拆一遍。这会丢掉已完成步骤的成果、浪费配额、可能反复横跳。**正确的重规划是增量的**：保留已 DONE 的步骤成果，只对「未完成 + 失败」的部分重新规划，并把「为什么要重规划」的原因喂给 Planner，让它有的放矢。

---

## 第二部分：是什么（反思器与重规划实现）

### 2.1 裁决枚举与反思结果

```java
package com.zero.ai.agentstudy.day10planningagent.reflection;

/** 反思裁决：决定主循环下一步走向。 */
public enum Verdict {
    CONTINUE,     // 继续下一步
    RETRY_STEP,   // 重试当前步骤
    REPLAN,       // 重新规划
    ABORT         // 放弃任务
}
```

```java
package com.zero.ai.agentstudy.day10planningagent.reflection;

/** 反思结果：裁决 + 理由（理由会喂给重规划的 Planner）。 */
public record Reflection(Verdict verdict, String reason) {
    public static Reflection of(Verdict v, String reason) {
        return new Reflection(v, reason);
    }
}
```

### 2.2 反思器接口

```java
package com.zero.ai.agentstudy.day10planningagent.reflection;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;

/** 反思器：根据刚执行的步骤结果与全局上下文，裁决下一步走向。 */
public interface Reflector {
    Reflection reflect(PlanStep justRun, StepResult result, PlanningContext ctx);
}
```

### 2.3 规则反思器（确定性，兜底）

先给一个不依赖 LLM 的规则反思器——快、可测、作为默认：

```java
package com.zero.ai.agentstudy.day10planningagent.reflection;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import org.springframework.stereotype.Component;

/** 规则反思器：基于步骤结果与失败次数做确定性裁决。 */
@Component
public class RuleBasedReflector implements Reflector {

    @Override
    public Reflection reflect(PlanStep justRun, StepResult result, PlanningContext ctx) {
        // 1) 成功 → 继续
        if (result.success()) {
            return Reflection.of(Verdict.CONTINUE, "步骤成功，继续执行");
        }
        // 2) 失败：先看预算是否还够重规划
        if (ctx.replanCount() >= ctx.goal().maxReplan()) {
            return Reflection.of(Verdict.ABORT, "重规划次数已达上限，放弃任务");
        }
        // 3) 该步骤失败次数少 → 高层重试一次
        if (justRun.attemptCount() < 2) {
            return Reflection.of(Verdict.RETRY_STEP, "步骤瞬时失败，重试当前步骤");
        }
        // 4) 反复失败 → 重规划（换个思路）
        return Reflection.of(Verdict.REPLAN,
                "步骤『%s』反复失败：%s，需要重新规划".formatted(justRun.description(), result.error()));
    }
}
```

### 2.4 LLM 反思器（智能，进阶）

生产里更强的是让 LLM 反思——它能理解「结果内容对不对」而非只看成败：

```java
package com.zero.ai.agentstudy.day10planningagent.reflection;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** LLM 反思器：让大模型评估进展并裁决。异常时降级为规则反思。 */
@Component
@Primary
public class LlmReflector implements Reflector {
    private final ChatClient chatClient;
    private final RuleBasedReflector fallback;

    public LlmReflector(ChatClient.Builder builder, RuleBasedReflector fallback) {
        this.chatClient = builder.build();
        this.fallback = fallback;
    }

    @Override
    public Reflection reflect(PlanStep justRun, StepResult result, PlanningContext ctx) {
        // 预算已尽，直接放弃（不浪费一次 LLM 调用）
        if (ctx.replanCount() >= ctx.goal().maxReplan() && !result.success()) {
            return Reflection.of(Verdict.ABORT, "重规划次数已达上限");
        }
        try {
            String prompt = """
                    你是任务反思器。根据以下信息，判断下一步该怎么办。
                    只能从 CONTINUE / RETRY_STEP / REPLAN / ABORT 中选一个。
                    总目标：%s
                    刚执行步骤：%s
                    执行是否成功：%s
                    输出/错误：%s
                    已完成步骤摘要：
                    %s
                    输出格式严格为：VERDICT|理由
                    """.formatted(
                    ctx.goal().description(), justRun.description(),
                    result.success(), result.success() ? result.output() : result.error(),
                    ctx.completedSummary());
            String raw = chatClient.prompt().user(prompt).call().content();
            return parse(raw);
        } catch (Exception e) {
           // LLM 不可用 → 降级到规则反思，保证系统可用
            return fallback.reflect(justRun, result, ctx);
        }
    }

    private Reflection parse(String raw) {
        String s = raw == null ? "" : raw.trim();
        String[] parts = s.split("\\|", 2);
        String head = parts[0].trim().toUpperCase();
        String reason = parts.length > 1 ? parts[1].trim() : s;
        try {
            return Reflection.of(Verdict.valueOf(head), reason);
        } catch (IllegalArgumentException ex) {
            // 解析不出合法裁决 → 保守选择继续（避免误判放弃）
            return Reflection.of(Verdict.CONTINUE, "反思输出无法解析，保守继续：" + s);
        }
    }
}
```

**要点**：`@Primary` 让 LLM 反思器为默认注入；LLM 异常时**降级到规则反思**，保证「模型挂了系统还能跑」——这是企业级的容错思维。

### 2.5 重规划：增量修正

在 `Planner` 接口上补一个 `replan` 方法（chapter-03 已预留概念，这里给实现）：

```java
package com.zero.ai.agentstudy.day10planningagent.core.planner;

import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;

public interface Planner {
    Plan plan(com.zero.ai.agentstudy.day10planningagent.core.Goal goal);

    /** 增量重规划：保留已完成成果，只针对未完成部分 + 失败原因重新规划。 */
    Plan replan(PlanningContext ctx, String reason);
}
```

`LlmPlanner` 里实现 `replan`（节选核心）：

```java
@Override
public Plan replan(PlanningContext ctx, String reason) {
    String doneSummary = ctx.completedSummary();      // 已完成步骤及成果
    String pending = ctx.plan().steps().stream()
            .filter(s -> s.status() != StepStatus.DONE && s.status() != StepStatus.SKIPPED)
            .map(s -> "- " + s.id() + ":" + s.description() + "(" + s.status() + ")")
            .collect(java.util.stream.Collectors.joining("\n"));

    String prompt = """
            原目标：%s
            已完成并保留的成果：
            %s
            尚未完成/失败的步骤：
            %s
            重规划原因：%s
            请只为「尚未完成的部分」重新生成步骤（保留已完成成果，不要重做），
            输出与初次规划相同的 JSON 结构。
            """.formatted(ctx.goal().description(), doneSummary, pending, reason);

    PlanDto dto = chatClient.prompt().user(prompt).call().entity(PlanDto.class);
    Plan newTail = toPlan(ctx.goal(), dto);
    // 合并：已完成步骤 + 新规划的未完成步骤
    return mergeKeepingDone(ctx.plan(), newTail);
}
```

合并逻辑 `mergeKeepingDone`：把原计划里 DONE/SKIPPED 的步骤原样保留，追加新规划出的步骤（重新分配不冲突的 id），确保「已完成的不重做」。

---

## 第三部分：怎么用（PlanningService 主循环 + REST 入口）

### 3.1 主循环——把一切串起来

这是整个 Planning Agent 的心脏：**规划 → 循环(调度→执行→观察→反思→按裁决走向) → 收尾**。

```java
package com.zero.ai.agentstudy.day10planningagent.service;

import com.zero.ai.agentstudy.day10planningagent.core.*;
import com.zero.ai.agentstudy.day10planningagent.core.planner.Planner;
import com.zero.ai.agentstudy.day10planningagent.context.*;
import com.zero.ai.agentstudy.day10planningagent.engine.Scheduler;
import com.zero.ai.agentstudy.day10planningagent.executor.StepExecutor;
import com.zero.ai.agentstudy.day10planningagent.reflection.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Planning Agent 主循环：规划→调度→执行→反思→(继续/重试/重规划/放弃)。 */
@Service
public class PlanningService {
    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    private final Planner planner;
    private final Scheduler scheduler;
    private final StepExecutor executor;
    private final Reflector reflector;

    public PlanningService(Planner planner, Scheduler scheduler,
                           StepExecutor executor, Reflector reflector) {
        this.planner = planner;
        this.scheduler = scheduler;
        this.executor = executor;
        this.reflector = reflector;
    }

    public PlanningContext run(Goal goal) {
        // ① 初始化上下文 + 规划
        PlanningContext ctx = new PlanningContext(goal);
        ctx.transitionTo(PlanState.PLANNING);
        Plan plan = planner.plan(goal);
        ctx.setPlan(plan);
        scheduler.validateNoCycle(plan);      // 环检测，病态计划立刻失败
        ctx.transitionTo(PlanState.READY);

        // ② 主循环
        while (!scheduler.isAllDone(ctx.plan())) {
            ctx.guardBudgetOrThrow();          // 预算护栏：步数/重规划/超时
            ctx.transitionTo(PlanState.EXECUTING);

            Optional<PlanStep> nextOpt = scheduler.nextStep(ctx.plan(), ctx);
            if (nextOpt.isEmpty()) {
                // 无就绪步骤但未全完成 → 有失败步骤卡住依赖链 → 重规划
                if (!tryReplan(ctx, "存在失败步骤导致无就绪步骤，需重规划")) {
                    ctx.transitionTo(PlanState.FAILED);
                    return ctx;
                }
                continue;
            }

            PlanStep step = nextOpt.get();
            StepResult result = executor.execute(step, ctx);   // 执行（内含低层重试）
            ctx.record(new Observation(step.id(), result.success() ? result.output() : result.error(),
                    result.success()));
            ctx.incrementStep();

            // ③ 反思裁决
            ctx.transitionTo(PlanState.REFLECTING);
            Reflection r = reflector.reflect(step, result, ctx);
            log.info("反思裁决：{} - {}", r.verdict(), r.reason());

            switch (r.verdict()) {
                case CONTINUE -> ctx.transitionTo(PlanState.EXECUTING);
                case RETRY_STEP -> {
                    step.retryWith(step.description());   // 重置为 PENDING，下轮重新调度
                    ctx.transitionTo(PlanState.EXECUTING);
                }
                case REPLAN -> {
                    if (!tryReplan(ctx, r.reason())) {
                        ctx.transitionTo(PlanState.FAILED);
                        return ctx;
                    }
                }
                case ABORT -> {
                    ctx.transitionTo(PlanState.FAILED);
                    log.warn("任务放弃：{}", r.reason());
                    return ctx;
                }
            }
        }

        // ④ 收尾：全部完成
        ctx.transitionTo(PlanState.SUCCEEDED);
        log.info("任务成功完成，共执行 {} 步，重规划 {} 次", ctx.stepCount(), ctx.replanCount());
        return ctx;
    }

    /** 尝试重规划；成功返回 true，预算耗尽返回 false。 */
    private boolean tryReplan(PlanningContext ctx, String reason) {
        if (ctx.replanCount() >= ctx.goal().maxReplan()) {
            log.warn("重规划已达上限 {}，不再重规划", ctx.goal().maxReplan());
            return false;
        }
        ctx.transitionTo(PlanState.RE_PLANNING);
        Plan merged = planner.replan(ctx, reason);
        scheduler.validateNoCycle(merged);
        ctx.setPlan(merged);
        ctx.incrementReplan();
        ctx.transitionTo(PlanState.READY);
        log.info("已完成第 {} 次重规划，原因：{}", ctx.replanCount(), reason);
        return true;
    }
}
```

**主循环讲解（逐段）**：
1. **① 规划阶段**：建 Context → PLANNING → 调 Planner 拆解 → 环检测 → READY。
2. **② 循环条件**：`!isAllDone` 且每轮先 `guardBudgetOrThrow`（步数/重规划/超时三重护栏，防死循环）。
3. **就绪为空分支**：说明有步骤失败卡住依赖链，尝试重规划，重规划也无路可走则 FAILED。
4. **执行 + 观察**：Executor 跑一步（内含低层重试），结果写入黑板 Observation，步数 +1。
5. **③ 反思**：进 REFLECTING，反思器裁决，按四裁决分别走向 CONTINUE/RETRY/REPLAN/ABORT。
6. **④ 收尾**：全 DONE 则 SUCCEEDED。

这就是「多步推理 + 动态规划 + 自我纠错 + 失败恢复」的完整闭环——Day10 全部核心能力在此汇聚。

### 3.2 REST 入口 Controller

```java
package com.zero.ai.agentstudy.day10planningagent.api;

import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.service.PlanningService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/day10/planning")
public class PlanningController {
    private final PlanningService service;

    public PlanningController(PlanningService service) { this.service = service; }

    @PostMapping("/run")
    public RunResponse run(@RequestBody RunRequest req) {
        Goal goal = Goal.of(req.goal(), req.maxSteps(), req.maxReplan(), req.timeoutMs());
        PlanningContext ctx = service.run(goal);
        return new RunResponse(
                ctx.state().name(),
                ctx.plan().prettyPrint(),
                ctx.completedSummary(),
                ctx.stepCount(),
                ctx.replanCount());
    }

    public record RunRequest(String goal, int maxSteps, int maxReplan, long timeoutMs) {}
    public record RunResponse(String finalState, String plan, String summary,
                              int steps, int replans) {}
}
```

### 3.3 调用示例

```bash
curl -X POST http://localhost:8080/api/day10/planning/run \
  -H "Content-Type: application/json" \
  -d '{
        "goal": "分析 GitHub Trending 上最热门的 AI Agent 项目并生成 Markdown 报告",
        "maxSteps": 15,
        "maxReplan": 3,
        "timeoutMs": 120000
      }'
```

返回：最终状态、计划全貌、已完成步骤摘要、总步数、重规划次数。

---

## 第四部分：用在哪

| Day10 能力 | 本章落地 |
|-----------|---------|
| Reflection 反思 | Reflector 四裁决（规则 + LLM 双实现） |
| Self-Correction 自我纠错 | RETRY_STEP / REPLAN 裁决闭环 |
| Re-Planning 动态重规划 | 增量 replan + mergeKeepingDone |
| Multi-Step Reasoning | 主循环逐步推进 + Observation 累积 |
| Failure Recovery（第二层） | 反思层的重规划兜底 |
| 状态机驱动 | 全程 transitionTo 显式流转 |
| 预算护栏 | guardBudgetOrThrow 防死循环 |

---

## 第五部分：避坑指南

1. **重规划必须增量**。保留 DONE 成果，只重规划未完成部分，否则丢结果、烧配额、反复横跳。

2. **反思要有预算上限**。maxReplan 到顶必须 ABORT，否则「失败→重规划→再失败」无限循环。

3. **LLM 反思必须能降级**。模型挂了要回退规则反思，不能让整个 Agent 瘫痪。

4. **裁决解析要保守**。LLM 输出无法解析时宁可 CONTINUE，也别误判 ABORT 白扔任务。

5. **就绪为空 ≠ 成功**。要区分「全做完」和「失败卡住」，后者需重规划。

6. **每轮循环先查预算**。护栏放循环头部，任何路径都逃不过预算检查。

7. **状态流转别跳步**。EXECUTING→REFLECTING→(EXECUTING/RE_PLANNING/FAILED)，遵守状态机转移表，非法转移 fail-fast。

8. **RETRY_STEP 要重置步骤状态**。用 retryWith 把 FAILED 步骤重置回 PENDING，否则调度器不会再选它。

---

## 本章小结

我们完成了 Planning Agent 的完整闭环：`Reflector`（规则 + LLM 四裁决）判断走向，`replan`（增量合并保留成果）动态修正计划，`PlanningService` 主循环把规划/调度/执行/反思/重规划串成能自我纠错的循环，`PlanningController` 暴露 `POST /api/day10/planning/run`。至此，一个能「拆解目标、按依赖调度、带重试执行、失败自省、动态重规划、预算护栏兜底」的企业级 Planning Agent 骨架全部打通。

**核心记忆**：反思四裁决、重规划要增量、预算护栏防死循环、LLM 组件必降级、状态机严格流转。

**下一章预告**：chapter-08 进阶——Tree Search 多方案探索、并行执行、可观测性（trace/指标）、状态持久化与人类介入（WAITING_HUMAN）。

---

## FAQ

**Q1：反思在「每步后」还是「失败后」触发？**
本实现每步后都反思（成功也反思，只是通常裁决 CONTINUE）。这样 LLM 反思器能在「成功但结果偏离目标」时提前 REPLAN，而不必等失败。

**Q2：RETRY_STEP 和 Executor 内部重试重复吗？**
不重复，是两层。Executor 内重试应对同一次调用的瞬时抖动（快、无脑重试）；反思 RETRY_STEP 是更高层的「这一步整体再来一次」决策（可能已换上下文）。

**Q3：如何防止「重规划反复横跳」？**
maxReplan 硬上限 + 重规划时带上「历史失败原因」让 Planner 避免重蹈覆辙 + 增量保留成果减少反复面。

---

## 面试高频题

1. 反思器的四种裁决分别在什么场景触发？
2. 为什么重规划要「增量」而非「从零」？如何实现增量合并？
3. LLM 反思器如何做容错降级？为什么解析失败要保守 CONTINUE？
4. 主循环如何用预算护栏防止死循环？
5. 「无就绪步骤但未全完成」在主循环里如何处理？为什么？

---

## 扩展阅读

- ReAct: Synergizing Reasoning and Acting in Language Models
- Reflexion: Language Agents with Verbal Reinforcement Learning
- Self-Refine / Self-Correction 相关论文
- 状态机驱动的编排引擎（如 AWS Step Functions）