# 第六章：Executor 执行器与工具体系——ToolRegistry / ToolSelector / 重试

> Scheduler 选出了步骤，接下来要真正「动手」。本章实现 Executor（执行器）+ 工具体系（Tool/ToolRegistry/ToolSelector）+ 重试策略，让每个 PlanStep 落地成一次工具调用。

---

## 第一部分：为什么学（核心价值）

### 1. Planner 动脑，Executor 动手——为什么必须分开？

Planner 输出的是「意图」（要做什么），Executor 负责「行动」（怎么做）。若不分开，规划逻辑和执行逻辑纠缠在一起，改工具就得改规划、改规划就得改执行，无法演进。分开后：Planner 只管拆解，Executor 只管调工具，二者通过 PlanStep 解耦。

### 2. 为什么要「工具注册表 + 工具选择器」？

Agent 的能力 = 它拥有的工具集。工具不能硬编码在 Executor 里（否则加一个工具要改核心代码）。用 **ToolRegistry** 统一登记工具（可插拔），用 **ToolSelector** 决定「这一步该用哪个工具」。这就是「Tool Selection」能力——Day10 学习目标之一。

### 3. 为什么执行必须带「重试」？

工具调用会失败：网络抖动、LLM 限流、页面临时不可用。**瞬时失败**不应立刻判定步骤失败——重试几次往往就好了。但重试要有上限（避免无限重试）和退避（避免雪崩）。这是「Failure Recovery」的第一道防线（第二道是反思重规划，chapter-07）。

---

## 第二部分：是什么（工具体系与执行器实现）

### 2.1 工具抽象 Tool

```java
package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;

/** 工具：Agent 的一项原子能力。 */
public interface Tool {
    /** 工具唯一名称，Planner 生成步骤时引用它。 */
    String name();

    /** 给 LLM/选择器看的能力描述。 */
    String description();

    /** 执行一步，返回文本结果。失败请抛异常，交给 Executor 的重试处理。 */
    String execute(PlanStep step, PlanningContext ctx) throws Exception;
}
```

### 2.2 两个示例工具

```java
package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import org.springframework.stereotype.Component;

/** 浏览器工具：抓取网页内容（此处用桩实现，chapter-09 接真实抓取）。 */
@Component
public class BrowserTool implements Tool {
    @Override public String name() { return "browser"; }
    @Override public String description() { return "抓取指定网页的 HTML 内容，用于浏览/采集类步骤"; }

    @Override
    public String execute(PlanStep step, PlanningContext ctx) throws Exception {
        // 真实实现见 chapter-09：调用 HttpClient 抓取 GitHub Trending 页面
        return "已抓取页面内容（模拟）：" + step.description();
    }
}
```

```java
package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/** LLM 工具：用大模型做提取/总结/排版等文本处理步骤。 */
@Component
public class LlmTool implements Tool {
    private final ChatClient chatClient;

    public LlmTool(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override public String name() { return "llm"; }
    @Override public String description() { return "用大模型完成提取、总结、Markdown 排版等文本处理步骤"; }

    @Override
    public String execute(PlanStep step, PlanningContext ctx) throws Exception {
        String prompt = """
                目标：%s
                当前步骤：%s
                已有上下文观察：
                %s
                请完成本步骤，直接输出结果文本。
                """.formatted(ctx.goal().description(), step.description(), ctx.completedSummary());
        return chatClient.prompt().user(prompt).call().content();
    }
}
```

### 2.3 工具注册表 ToolRegistry

Spring 会把所有 `Tool` 实现注入成 List，注册表按 name 建索引：

```java
package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import org.springframework.stereotype.Component;

import java.util.*;

/** 工具注册表：按名称查工具，可插拔。 */
@Component
public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    public ToolRegistry(List<Tool> allTools) {         // Spring 自动注入所有 Tool Bean
        for (Tool t : allTools) tools.put(t.name(), t);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> all() { return tools.values(); }

    /** 供 Planner/Selector 参考的能力清单文本。 */
    public String capabilities() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            sb.append("- ").append(t.name()).append("：").append(t.description()).append("\n");
        }
        return sb.toString();
    }
}
```

### 2.4 工具选择器 ToolSelector

`PlanStep` 里已有一个 `suggestedTool` 字段（Planner 拆解时给的建议）。选择器策略：**优先用建议工具，找不到则按关键词兜底**。

```java
package com.zero.ai.agentstudy.day10planningagent.executor;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.executor.tool.Tool;
import com.zero.ai.agentstudy.day10planningagent.executor.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 工具选择器：决定一步用哪个工具。 */
@Component
public class ToolSelector {
    private final ToolRegistry registry;

    public ToolSelector(ToolRegistry registry) { this.registry = registry; }

    public Tool select(PlanStep step) {
        // 1) 优先采用 Planner 给出的建议工具
        if (step.suggestedTool() != null) {
            Optional<Tool> byName = registry.find(step.suggestedTool());
            if (byName.isPresent()) return byName.get();
        }
        // 2) 关键词兜底：含「抓取/浏览/页面」用 browser，否则默认 llm
        String d = step.description();
        if (d.contains("抓取") || d.contains("浏览") || d.contains("页面") || d.contains("采集")) {
            return registry.find("browser").orElseGet(() -> registry.find("llm").orElseThrow());
      }
        return registry.find("llm")
                .orElseThrow(() -> new IllegalStateException("无可用工具处理步骤：" + step.id()));
    }
}
```

**升级空间**：生产里可用 LLM 做工具选择（把 `registry.capabilities()` 喂给模型让它选）。这里用「建议 + 关键词」的确定性策略，可控可测，chapter-08 再讲 LLM 选择。

### 2.5 执行器 Executor（带重试与退避）

```java
package com.zero.ai.agentstudy.day10planningagent.executor;

import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;
import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.executor.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 执行器：选工具 → 调用 → 带重试 → 写回步骤状态。 */
@Component
public class StepExecutor {
    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);

    private final ToolSelector selector;

    @Value("${zero.planning.max-retry-per-step:2}")
    private int maxRetryPerStep;

    @Value("${zero.planning.retry-backoff-ms:500}")
    private long backoffMs;

    public StepExecutor(ToolSelector selector) { this.selector = selector; }

    public StepResult execute(PlanStep step, PlanningContext ctx) {
        Tool tool = selector.select(step);
        step.markRunning();
        int attempt = 0;
        Exception last = null;
        while (attempt <= maxRetryPerStep) {
            try {
                String output = tool.execute(step, ctx);
                step.markDone(output);
                log.info("步骤 {} 执行成功（工具={}，第{}次尝试）", step.id(), tool.name(), attempt + 1);
                return StepResult.success(step.id(), output);
            } catch (Exception e) {
                last = e;
                attempt++;
            log.warn("步骤 {} 第{}次执行失败：{}", step.id(), attempt, e.getMessage());
                if (attempt <= maxRetryPerStep) {
                    sleep(backoffMs * attempt);   // 线性退避：500ms,1000ms...
                }
            }
        }
        String msg = last == null ? "未知错误" : last.getMessage();
        step.markFailed(msg);
        log.error("步骤 {} 重试{}次后仍失败：{}", step.id(), maxRetryPerStep, msg);
        return StepResult.failure(step.id(), msg);
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
```

**代码讲解**：
- `markRunning` 在重试外，避免每次尝试都翻转状态。
- 循环 `attempt` 从 0 到 `maxRetryPerStep`（含），即「首次 + N 次重试」共 N+1 次机会。
- 线性退避 `backoffMs * attempt`，避免立即重试打爆下游。
- 全部失败才 `markFailed` 并返回 failure；成功立即 `markDone` 返回 success。
- 重试用尽后**不抛异常**，而是返回 failure——把「该步骤失败」的决策权交给上层反思（chapter-07），Executor 不越权判定「整个计划失败」。

---

## 第三部分：怎么用（跑一步 + 观察重试）

### 3.1 正常执行

```java
PlanStep step = new PlanStep("step-1", "抓取 GitHub Trending 页面",
        List.of(), Priority.HIGH, "browser");
StepResult r = stepExecutor.execute(step, ctx);
// r.success() == true, step.status()==DONE, step.result() 为抓取内容
ctx.record(new Observation(step.id(), r.output(), true));
```

### 3.2 模拟瞬时失败触发重试

假设 `BrowserTool` 前两次抛异常、第三次成功：

```
尝试1：抛 IOException → warn，退避 500ms
尝试2：抛 IOException → warn，退避 1000ms
尝试3：成功         → markDone，返回 success（共 3 次机会用满前成功）
```

若三次全失败（maxRetryPerStep=2）：

```
尝试1 失败 → 退避500ms
尝试2 失败 → 退避1000ms
尝试3 失败 → markFailed，返回 failure
→ 交给反思判定：RETRY_STEP / REPLAN / ABORT（chapter-07）
```

### 3.3 配置项（application.yml）

```yaml
zero:
  planning:
    max-retry-per-step: 2       # 每步最多重试次数（不含首次）
    retry-backoff-ms: 500       # 退避基数，线性递增
```

---

## 第四部分：用在哪

| 能力点 | 本章对应实现 |
|--------|-------------|
| Tool Selection | ToolSelector：建议工具 + 关键词兜底 |
| Plan Execution | StepExecutor：选工具→调用→写回 |
| Failure Recovery（第一层） | 重试 + 线性退避 |
| 可插拔工具 | ToolRegistry + Spring List 注入 |
| 能力自描述 | Tool.description() / registry.capabilities() |

**扩展方向**：接入真实工具（HTTP 抓取、文件读写、SQL 查询、代码执行沙箱）只需实现 `Tool` 接口并标 `@Component`，注册表自动收录，Executor 无需改动——这就是可插拔架构的威力。

---

## 第五部分：避坑指南

1. **别让 Executor 判定「整个计划失败」**。它只负责「这一步」的成败，计划级决策交反思层。越权会破坏职责分离。

2. **重试必须有上限**。无上限重试遇到永久性错误（如 404）会死循环，白烧配额。

3. **重试要退避**。立即重试会在下游故障时形成雪崩，放大问题。

4. **区分瞬时错误与永久错误**。理想情况下 429/超时才重试，400/404 不该重试（进阶：按异常类型决定，chapter-08）。

5. **工具别硬编码进 Executor**。用注册表 + 接口，加工具零改核心。

6. **markRunning 别放进重试循环**。否则状态反复翻转，日志与状态机混乱。

7. **Thread.sleep 记得处理中断**。捕获 InterruptedException 后要 `Thread.currentThread().interrupt()` 恢复中断标志。

8. **工具选择要有兜底**。建议工具不存在时不能崩，要降级到默认工具或明确报错。

---

## 本章小结

我们建立了完整的执行链：`Tool`（能力抽象）→ `ToolRegistry`（可插拔登记）→ `ToolSelector`（选工具）→ `StepExecutor`（调用 + 重试退避 + 写回）。执行器只对「单步」负责，失败时返回 failure 交上层反思，绝不越权判定计划成败。

**核心记忆**：工具可插拔、选择有兜底、重试有上限有退避、Executor 只管单步不管全局。

**下一章预告**：chapter-07 是全篇高潮——`Reflection` 反思器（四裁决）+ 重规划 + `PlanningService` 主循环 + REST `Controller`，把 Planner/Scheduler/Executor 串成能自我纠错的完整闭环。

---

## FAQ

**Q1：ToolSelector 为什么不直接用 LLM 选？**
可以，但确定性策略更可测、更省 token、延迟更低。生产中常「简单场景规则选、复杂场景 LLM 选」混合。chapter-08 给 LLM 选择器版本。

**Q2：重试和反思重规划有什么区别？**
重试是「同一步、同工具、原样再来」，应对瞬时抖动；重规划是「改计划」，应对方向性错误。重试是战术级，重规划是战略级。

**Q3：为什么失败返回 failure 而不抛异常？**
异常会中断主循环；返回 failure 让主循环能优雅地进入反思阶段决策。把「异常」转成「结果」，是编排系统的常见模式。

---

## 面试高频题

1. Planner 与 Executor 为什么要分离？
2. 工具注册表 + 接口的可插拔设计解决了什么问题？
3. 重试为什么要有上限和退避？线性退避与指数退避的区别？
4. 瞬时错误与永久错误该如何区分处理？
5. Executor 为什么不应判定整个计划的成败？

---

## 扩展阅读

- Spring Retry / Resilience4j 的重试与退避策略
- LangChain / AutoGPT 的 Tool 抽象设计
- 断路器（Circuit Breaker）模式
- Spring 依赖注入：List<Interface> 自动收集所有实现