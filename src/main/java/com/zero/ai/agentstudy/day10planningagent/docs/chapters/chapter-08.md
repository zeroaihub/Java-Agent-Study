# 第八章：进阶——Tree Search 多方案探索、并行执行、可观测性与持久化

> 前七章已经打通了「能自我纠错的 Planning Agent 骨架」。本章讲把它推向生产的四个进阶主题：多方案探索（Tree Search）、并行执行、可观测性、状态持久化与人类介入。每个主题给出可落地的设计与关键代码，让 Agent 从「能跑」走向「跑得稳、跑得快、看得见、可恢复」。

---

## 第一部分：为什么学（核心价值）

单路径的 Planning Agent 有四个生产短板：

1. **只探一条路**——LLM 拆的计划不一定最优，一条道走到黑可能次优。→ Tree Search 探索多方案择优。
2. **纯串行慢**——无依赖步骤本可并行，串行白白等待。→ 并行执行提速。
3. **黑盒难排障**——线上出问题，不知道 Agent 在哪一步、为什么重规划。→ 可观测性（trace + 指标）。
4. **进程一挂全丢**——长任务跑到一半崩了，从头再来。→ 持久化 + 人类介入（WAITING_HUMAN）。

---

## 第二部分：是什么（四大进阶主题）

### 2.1 Tree Search：多方案探索

**思想**：不只让 Planner 生成一个 Plan，而是生成多个候选计划（分支），对每个分支评估「预期收益/可行性」，择优执行；执行中某分支失败，可回退到另一分支。这就是把「线性规划」升级为「搜索树」。

```
                    Goal
                   /  |  \
             Plan-A Plan-B Plan-C     ← 生成多个候选计划（分支）
               |      |      |
           评分0.6 评分0.9 评分0.4    ← 对每个计划打分（LLM/启发式）
                      ↓
                  选 Plan-B 执行        ← 择优
                      ↓
                 若 B 中途受阻
                      ↓
                  回退到 Plan-A 重试     ← 回溯
```

关键接口设计（在 Planner 上扩展多候选）：

```java
package com.zero.ai.agentstudy.day10planningagent.core.planner;

import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import java.util.List;

/** 支持多候选计划的规划器（Tree Search 用）。 */
public interface MultiPlanPlanner {
    /** 为目标生成 n 个候选计划分支。 */
    List<Plan> propose(Goal goal, int n);
}
```

评分与择优：

```java
package com.zero.ai.agentstudy.day10planningagent.engine;

import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** 计划评分器：给候选计划打分（可行性/步数/风险），择优。 */
@Component
public class PlanScorer {
    private final ChatClient chatClient;

    public PlanScorer(ChatClient.Builder builder) { this.chatClient = builder.build(); }

    /** 返回 0~1 的评分，越高越好。 */
    public double score(Plan plan) {
        String prompt = """
                给以下执行计划打分（0~1），综合考虑：可行性、步骤简洁度、失败风险。
                只输出一个小数：
                %s
                """.formatted(plan.prettyPrint());
        try {
            return Double.parseDouble(chatClient.prompt().user(prompt).call().content().trim());
        } catch (Exception e) {
            // 解析失败给中性分，或用启发式：步数越少分越高
            return 1.0 / (1 + plan.steps().size());
        }
    }
}
```

**成本提示**：Tree Search 会成倍消耗 token（n 个计划 + n 次评分）。生产里通常 n=2~3，且只在「首次规划」或「重要目标」时启用，普通场景用单计划省成本。

### 2.2 并行执行：无依赖步骤同时跑

chapter-05 的 `readySteps` 已为并行预留——它返回**全部**就绪步骤。并行调度器用线程池同时执行它们：

```java
package com.zero.ai.agentstudy.day10planningagent.engine;

import com.zero.ai.agentstudy.day10planningagent.core.*;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.executor.StepExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;

/** 并行执行器：把当前就绪的一批步骤并发执行。 */
@Component
public class ParallelExecutor {
    private final StepExecutor stepExecutor;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public ParallelExecutor(StepExecutor stepExecutor) { this.stepExecutor = stepExecutor; }

    public List<StepResult> executeBatch(List<PlanStep> readyBatch, PlanningContext ctx) {
        List<Future<StepResult>> futures = readyBatch.stream()
                .map(step -> pool.submit(() -> stepExecutor.execute(step, ctx)))
                .toList();
        return futures.stream().map(f -> {
            try { return f.get(); }
            catch (Exception e) { throw new CompletionException(e); }
        }).toList();
    }
}
```

**并行的隐藏难点——共享写冲突**：多个步骤同时写 `PlanningContext` 的 observations/blackboard，必须线程安全。改造 Context：

```java
// PlanningContext 里
private final List<Observation> observations =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
private final java.util.Map<String, Object> blackboard =
        new java.util.concurrent.ConcurrentHashMap<>();
```

**关键约束**：只能并行「相互无依赖」的步骤。有依赖的必须串行等待前置完成——这正是 DAG 就绪判定保证的（就绪集合里的步骤天然无相互依赖）。

### 2.3 可观测性：trace + 指标

线上排障靠三样：**结构化日志、执行轨迹（trace）、指标（metrics）**。

轨迹记录——给 Context 加一个事件流：

```java
package com.zero.ai.agentstudy.day10planningagent.context;

import java.time.Instant;

/** 执行轨迹事件：时间 + 阶段 + 详情，用于回放与排障。 */
public record TraceEvent(Instant at, String phase, String detail) {
    public static TraceEvent of(String phase, String detail) {
        return new TraceEvent(Instant.now(), phase, detail);
    }
}
```

在主循环关键节点埋点（示意）：

```java
ctx.trace(TraceEvent.of("PLAN", "生成计划，步数=" + plan.steps().size()));
ctx.trace(TraceEvent.of("EXECUTE", "执行 " + step.id() + " 工具=" + tool));
ctx.trace(TraceEvent.of("REFLECT", "裁决=" + r.verdict() + " 理由=" + r.reason()));
ctx.trace(TraceEvent.of("REPLAN", "第" + ctx.replanCount() + "次重规划"));
```

指标（用 Micrometer，Spring Boot Actuator 自带）：

```java
// 计数：规划次数、重规划次数、步骤成功/失败数、任务成败
meterRegistry.counter("planning.replan.total").increment();
meterRegistry.counter("planning.step.result", "status", result.success() ? "ok" : "fail").increment();
// 计时：单步耗时、整任务耗时
Timer.Sample sample = Timer.start(meterRegistry);
// ... 执行 ...
sample.stop(meterRegistry.timer("planning.step.duration"));
```

暴露 `/actuator/prometheus`，接 Grafana 就能看「重规划率、步骤失败率、任务时长分布」等 Agent 健康度指标。

### 2.4 持久化与人类介入（WAITING_HUMAN）

**持久化**：长任务要能断点续跑。把 `PlanningContext` 的核心状态（goal/plan/state/observations/计数器）序列化存 DB 或 Redis，每次状态流转后落盘；进程重启后按 planId 加载恢复。

```java
package com.zero.ai.agentstudy.day10planningagent.context;

/** 上下文持久化：存/取 Planning 状态，支持断点续跑。 */
public interface ContextStore {
    void save(String planId, PlanningContext ctx);
    java.util.Optional<PlanningContext> load(String planId);
}
```

实现可用 Redis（`RedisContextStore`）或 JPA（`JpaContextStore`）——接口隔离，切换存储零改主循环。

**人类介入（Human-in-the-Loop）**：某些步骤风险高（如删库、大额转账、发布外部内容），Agent 不该自动执行，要停下等人审批。这就是 `WAITING_HUMAN` 状态的用途：

```java
// 主循环里，遇到需审批的步骤
if (step.requiresApproval()) {
    ctx.transitionTo(PlanState.WAITING_HUMAN);
    contextStore.save(ctx.goal().id(), ctx);   // 落盘，挂起等待
    return ctx;                                 // 返回，等外部审批接口唤醒
}
```

审批接口唤醒：

```java
@PostMapping("/approve/{planId}")
public RunResponse approve(@PathVariable String planId, @RequestBody ApproveRequest req) {
    PlanningContext ctx = contextStore.load(planId).orElseThrow();
    if (req.approved()) {
        ctx.transitionTo(PlanState.EXECUTING);   // 批准 → 继续
        return toResponse(service.resume(ctx));   // 从挂起点续跑
    } else {
        ctx.transitionTo(PlanState.FAILED);       // 拒绝 → 终止
        return toResponse(ctx);
    }
}
```

---

## 第三部分：怎么用（组合进阶能力的落地策略）

不是所有进阶都要一次上齐。推荐渐进式启用：

| 阶段 | 启用能力 | 收益 | 成本 |
|------|---------|------|------|
| MVP | 单计划 + 串行 + 日志 | 快速跑通 | 最低 |
| 提速 | 并行执行 | 无依赖步骤并发，时长↓ | 线程安全改造 |
| 提质 | Tree Search（n=2~3） | 计划质量↑ | token 成倍 |
| 上线 | 可观测性（trace+指标） | 可排障、可监控 | 埋点工作量 |
| 生产 | 持久化 + 人类介入 | 断点续跑、风险管控 | 存储 + 审批流 |

**配置开关**（application.yml）——让进阶能力可插拔：

```yaml
zero:
  planning:
    tree-search:
      enabled: false          # 是否多方案探索
      candidates: 3           # 候选计划数
    parallel:
      enabled: false          # 是否并行执行
      pool-size: 4
    persistence:
      enabled: false          # 是否持久化断点续跑
    human-in-loop:
    enabled: true           # 高风险步骤是否需审批
```

---

## 第四部分：用在哪

| 进阶能力 | 典型生产场景 |
|---------|-------------|
| Tree Search | 重要决策类任务、方案对比、代码生成择优 |
| 并行执行 | 多源数据采集、批量文件处理、并发 API 调用 |
| 可观测性 | 所有线上 Agent（无一例外，出问题必须能查） |
| 持久化 | 长耗时任务（数小时的数据管道、批处理） |
| 人类介入 | 金融交易、生产发布、内容对外发布、删除类操作 |

---

## 第五部分：避坑指南

1. **Tree Search 别无脑开**。token 成倍，普通任务不值。只在重要目标/首次规划开，n 控制在 2~3。

2. **并行必须保证 Context 线程安全**。observations 用同步集合、blackboard 用 ConcurrentHashMap，否则数据错乱。

3. **只并行无依赖步骤**。有依赖的并行会读到未完成的前置结果，逻辑必错。

4. **线程池要有界并可关闭**。无界池会 OOM；应用关闭时 `pool.shutdown()` 避免线程泄漏。

5. **可观测性从第一天就埋点**。事后补埋点极痛苦。trace/指标是排障的生命线。

6. **持久化要在每次状态流转后**。只在开头/结尾存，崩溃时丢中间进度。

7. **人类介入要有超时**。审批不能无限等待，超时应有默认策略（拒绝/升级），否则任务永久挂起。

8. **高风险步骤识别别漏**。删除、转账、对外发布必须走审批，宁可多拦不可漏放。

---

## 本章小结

我们把 Planning Agent 从「能跑的骨架」推向「生产级」：Tree Search 多方案探索择优、并行执行提速（配套线程安全的 Context）、可观测性（trace + Micrometer 指标）让 Agent 可排障可监控、持久化支持断点续跑、WAITING_HUMAN 实现高风险步骤的人类审批。所有能力用配置开关可插拔，按 MVP→提速→提质→上线→生产 渐进启用。

**核心记忆**：进阶能力都要「可开关、按需启用」；并行必须线程安全且只并行无依赖；可观测性尽早埋点；高风险步骤必须人类介入。

**下一章预告**：chapter-09 收官——用真实的「分析 GitHub Trending 最热 AI Agent 项目并生成 Markdown」端到端 Demo，把全部模块串起来跑通，并讲如何接入 ZeroHub AI Agent Platform。

---

## FAQ

**Q1：Tree Search 和重规划有什么区别？**
Tree Search 是「一开始就探索多方案择优」（宽度优先），重规划是「执行中发现不对再改」（深度纠错）。二者可结合：探出好计划再执行，执行中仍可重规划。

**Q2：并行度设多少合适？**
受限于工具的下游承载（如 API 限流）和内存。IO 密集（网络抓取）可 4~8，CPU 密集看核数。一定要压测，别拍脑袋。

**Q3：持久化存整个 Context 还是增量？**
中小任务存整个快照简单可靠；超大任务可存事件流（Event Sourcing）增量重放。多数场景快照够用。

---

## 面试高频题

1. Tree Search 如何提升计划质量？成本代价是什么？
2. 并行执行的前提条件是什么？如何保证共享状态线程安全？
3. 一个生产级 Agent 的可观测性应包含哪些内容？
4. 如何用持久化实现 Agent 的断点续跑？
5. 什么样的步骤需要人类介入？WAITING_HUMAN 状态如何流转与唤醒？

---

## 扩展阅读

- Tree of Thoughts / Monte Carlo Tree Search 在 LLM Agent 的应用
- Java 并发：ExecutorService、CompletableFuture、线程安全集合
- Micrometer + Prometheus + Grafana 可观测性栈
- Event Sourcing 与 Saga 模式（长事务/断点续跑）
- Human-in-the-Loop 系统设计