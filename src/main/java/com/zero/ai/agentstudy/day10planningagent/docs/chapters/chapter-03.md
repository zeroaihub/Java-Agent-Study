# 第三章：planner-core 领域模型与 LlmPlanner

> 本章我们从概念走进代码。目标：搭好 `planner-core` 领域模型（Goal / Plan / PlanStep / 枚举 / StepResult），并用 Spring AI 实现能真正「拆解目标」的 `LlmPlanner`。本章所有代码都可编译、可运行、可测试，全部落在 `com.zero.ai.agentstudy.day10planningagent` 包下，绝不触碰前九天代码。

---

## 第一部分：为什么学（核心价值）

### 1. 为什么先写领域模型，而不是先写 Controller？

企业级项目的第一行代码应该是**领域模型（Domain Model）**，不是 Controller，也不是数据库表。因为领域模型是整个系统的「词汇表」——所有人（Planner、Executor、Reflector）都围绕这套模型对话。模型定得好，后面所有代码都顺；模型定歪了，后面全是补丁。

这就是「领域驱动设计（DDD）」的核心思想：**先用代码把业务语言表达清楚，技术细节后置。** 第二章我们已经把业务语言（Goal/Plan/Step……）讲透了，本章就是把它翻译成 Java 类型。

### 2. 为什么 LlmPlanner 是整个 Agent 的「大脑起点」？

Planner 是 Agent 从「一句模糊的话」变成「一串可执行步骤」的地方。这是 Agent 区别于普通程序的分水岭：普通程序的步骤是程序员写死的，Agent 的步骤是 LLM **当场想出来的**。`LlmPlanner` 就是承载这个「当场想」的组件。写好它，你就掌握了「如何让 LLM 稳定输出结构化计划」这一 Agent 工程最核心的技能。

### 3. 为什么这一步最能体现「工程 vs 玩具」的差距？

让 LLM「随便拆个计划」很容易，一句 Prompt 就行。但要让它**稳定输出可被 Java 解析的 JSON、字段齐全、依赖合法、能容错重试**——这中间全是工程。本章会带你把「玩具级 Prompt」升级成「生产级结构化输出」。

---

## 第二部分：是什么（领域模型逐类实现）

### 2.1 包结构规划

```
day10planningagent/
 └── core/                         ← 本章：planner-core
     ├── Goal.java                 目标（record）
     ├── Priority.java             优先级枚举
     ├── StepStatus.java           步骤状态枚举
     ├── PlanStep.java             步骤（可变，含运行时状态）
     ├── Plan.java                 计划（步骤集合）
     ├── StepResult.java           执行结果（record）
     └── planner/
         ├── Planner.java          规划器接口
         └── LlmPlanner.java       基于 Spring AI 的实现
```

### 2.2 枚举：Priority 与 StepStatus

先写最底层、无依赖的枚举。它们是模型的「原子」。

```java
package com.zero.ai.agentstudy.day10planningagent.core;

/** 步骤优先级：多个步骤同时就绪时的排序依据。数值越大越优先。 */
public enum Priority {
    LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);

    private final int weight;
    Priority(int weight) { this.weight = weight; }
    public int weight() { return weight; }
}
```

```java
package com.zero.ai.agentstudy.day10planningagent.core;

/** 步骤生命周期状态。 */
public enum StepStatus {
    PENDING,   // 等待执行
    RUNNING,   // 执行中
    DONE,      // 成功完成
    FAILED,    // 执行失败
    SKIPPED    // 被跳过（如重规划后废弃）
}
```

**为什么 Priority 带 weight**：将来 Scheduler 排序时直接用 `weight()` 比较，比用 `enum.ordinal()` 更明确、更抗「枚举顺序被人误调」的风险。

### 2.3 Goal：不可变的目标 + 预算

```java
package com.zero.ai.agentstudy.day10planningagent.core;

/**
 * 目标：Planning 的起点与终止判据。
 * 用 record 表达「不可变值对象」——目标一旦下达，其内容和预算不应被中途篡改。
 */
public record Goal(
        String id,
        String description,   // 自然语言目标
        int maxSteps,         // 预算：最多执行步数
        int maxReplan,        // 预算：最多重规划次数
        long timeoutMs        // 预算：整体超时（毫秒）
) {
    /** 便捷工厂：给定描述，用默认预算创建目标。 */
    public static Goal of(String description) {
        return new Goal(
                "goal-" + System.currentTimeMillis(),
                description,
                20,           // 默认最多 20 步
                3,            // 默认最多重规划 3 次
                120_000L      // 默认 2 分钟超时
        );
    }
}
```

**为什么用 record**：Goal 是「值对象」——两个内容相同的 Goal 就该相等，且不应被修改。record 天然提供不可变、equals/hashCode、简洁构造，是值对象的最佳载体。

### 2.4 PlanStep：可变的步骤（承载运行时状态）

Goal 不可变，但 PlanStep **必须可变**——因为它的状态（PENDING→RUNNING→DONE）和结果会在执行中不断变化。

```java
package com.zero.ai.agentstudy.day10planningagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划步骤 / 子任务。可变对象：状态与结果随执行演进。
 */
public class PlanStep {

    private final String id;             // 如 "step-1"
    private final String description;    // 这一步做什么
    private final List<String> dependsOn;// 依赖的前置步骤 id
    private final Priority priority;
    private String suggestedTool;        // Planner 建议的工具（可空）

    private StepStatus status = StepStatus.PENDING;
    private String result;               // 执行后的观察结果
    private int retryCount = 0;          // 已重试次数

    public PlanStep(String id, String description, List<String> dependsOn,
                    Priority priority, String suggestedTool) {
        this.id = id;
        this.description = description;
        this.dependsOn = dependsOn == null ? new ArrayList<>() : new ArrayList<>(dependsOn);
        this.priority = priority == null ? Priority.MEDIUM : priority;
        this.suggestedTool = suggestedTool;
  }

    // ---- 状态流转（只暴露语义化方法，不暴露裸 setter）----
    public void markRunning() { this.status = StepStatus.RUNNING; }
    public void markDone(String result) { this.status = StepStatus.DONE; this.result = result; }
    public void markFailed(String reason) { this.status = StepStatus.FAILED; this.result = reason; }
    public void markSkipped() { this.status = StepStatus.SKIPPED; }

    /** 用新建议参数重试同一步（Self-Correction 的一种）。 */
    public void retryWith(String newHint) {
        this.retryCount++;
        this.status = StepStatus.PENDING;
        if (newHint != null && !newHint.isBlank()) {
            this.description = this.description + "（修正提示：" + newHint + "）";
        }
    }

    // ---- getters ----
    public String id() { return id; }
    public String description() { return description; }
    public List<String> dependsOn() { return dependsOn; }
    public Priority priority() { return priority; }
    public String suggestedTool() { return suggestedTool; }
    public void setSuggestedTool(String tool) { this.suggestedTool = tool; }
    public StepStatus status() { return status; }
    public String result() { return result; }
    public int retryCount() { return retryCount; }
    public boolean isDone() { return status == StepStatus.DONE; }
    public boolean isFailed() { return status == StepStatus.FAILED; }

    @Override
    public String toString() {
        return "%s[%s] deps=%s pri=%s tool=%s status=%s"
                .formatted(id, description, dependsOn, priority, suggestedTool, status);
    }
}
```

> 注意：上面 `retryWith` 里改了 `description`，但字段声明为 `final`，这会编译报错。这是一个**故意留下的教学陷阱**——下面「避坑」会讲。正确做法是把 `description` 改为非 final，或用单独的 `hint` 字段。我们在第三部分给出修正版。

**为什么不暴露裸 setter、只暴露 `markXxx()`**：这叫「充血模型」——让对象自己管理自己的状态转移，外部不能随便把 status 设成任意值。这是防止状态被乱改的第一道防线（第二道是 PlanState 状态机）。

### 2.5 Plan：计划（步骤集合 + 便捷查询）

```java
package com.zero.ai.agentstudy.day10planningagent.core;

import java.util.List;
import java.util.Optional;

/** 计划：为达成 Goal 生成的一组步骤。 */
public class Plan {

    private final String id;
    private final String goalId;
    private final List<PlanStep> steps;

    public Plan(String id, String goalId, List<PlanStep> steps) {
        this.id = id;
        this.goalId = goalId;
        this.steps = steps;
    }

    public String id() { return id; }
    public String goalId() { return goalId; }
    public List<PlanStep> steps() { return steps; }

    public Optional<PlanStep> findById(String stepId) {
        return steps.stream().filter(s -> s.id().equals(stepId)).findFirst();
    }

    /** 是否所有步骤都已 DONE 或 SKIPPED（终态）。 */
    public boolean allSettled() {
        return steps.stream().allMatch(s ->
                s.status() == StepStatus.DONE || s.status() == StepStatus.SKIPPED);
    }

    public String prettyPrint() {
        StringBuilder sb = new StringBuilder("Plan[").append(id).append("]\n");
        for (PlanStep s : steps) sb.append("  ").append(s).append("\n");
        return sb.toString();
    }
}
```

### 2.6 StepResult：执行结果（值对象）

```java
package com.zero.ai.agentstudy.day10planningagent.core;

/** 执行一步后的结果。ok=true 表示工具调用成功，output 是观察结果，error 是失败原因。 */
public record StepResult(boolean ok, String output, String error) {
    public static StepResult success(String output) { return new StepResult(true, output, null); }
    public static StepResult failure(String error)  { return new StepResult(false, null, error); }
}
```

### 2.7 Planner 接口

```java
package com.zero.ai.agentstudy.day10planningagent.core.planner;

import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.core.Plan;

/**
 * 规划器：把 Goal 拆解成 Plan。只「动脑」，不「动手」。
 * ctx 参数在本章暂用 Object 占位，第四章引入 PlanningContext 后替换为真实类型。
 */
public interface Planner {
    Plan plan(Goal goal);
    Plan replan(Goal goal, String reason, Plan previous);
}
```

> 说明：为了让本章能独立编译（PlanningContext 在第四章才实现），Planner 接口先用「Goal + reason + previous」的形式，等第四章有了 Context 再演进为 `plan(goal, ctx)`。这种「先能跑，再演进」的渐进式开发，正是本课程强调的工程节奏。

### 2.8 LlmPlanner：让 LLM 稳定输出结构化计划（核心）

这是本章的重头戏。难点不在「调 LLM」，而在「让 LLM 输出**能被 Java 解析的、字段合法的 JSON**」。我们用 Spring AI 的 `ChatClient` + 「结构化输出」思路来实现。

**第一步：定义 LLM 要返回的 DTO（与领域模型解耦的传输对象）**

```java
package com.zero.ai.agentstudy.day10planningagent.core.planner;

import java.util.List;

/** LLM 规划输出的 DTO。故意与领域模型 PlanStep 分离——LLM 只管吐 JSON，映射由我们控制。 */
public record PlanDto(List<StepDto> steps) {
    public record StepDto(
            String id,
            String description,
            List<String> dependsOn,
            String priority,     // LOW/MEDIUM/HIGH/CRITICAL
            String suggestedTool // 建议工具名，可空
    ) {}
}
```

**为什么 DTO 和领域模型分开**：LLM 输出是「外部不可信数据」，DTO 是它的落脚点；领域模型是「内部可信对象」。中间做一次显式映射+校验，脏数据进不了核心域。这是防腐层（Anti-Corruption Layer）思想。

**第二步：写 Prompt 模板（放 resources 或常量）**

```java
private static final String PLAN_SYSTEM_PROMPT = """
    你是一个任务规划专家。请把用户目标拆解为一组可执行的步骤，并只返回 JSON。
    规则：
    1. 每个步骤要「小而具体、可被单一工具执行」。
    2. 用 dependsOn 表达步骤间的执行依赖（前置步骤的 id 列表），无依赖则为空数组。
    3. priority 从 [LOW, MEDIUM, HIGH, CRITICAL] 中选。
    4. suggestedTool 从可用工具里选：[browser, extract, summary, markdown]，不确定填 null。
    5. 步骤 id 用 "step-1"、"step-2" 递增。
    6. 只输出 JSON，不要任何解释文字、不要 markdown 代码块围栏。
    JSON 结构：
    { "steps": [ { "id": "...", "description": "...", "dependsOn": [], "priority": "HIGH", "suggestedTool": "browser" } ] }
    """;
```

**第三步：实现 LlmPlanner**

```java
package com.zero.ai.agentstudy.day10planningagent.core.planner;

import com.zero.ai.agentstudy.day10planningagent.core.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class LlmPlanner implements Planner {

    private final ChatClient chatClient;

    // 构造注入 Spring AI 的 ChatClient.Builder（自动配置好模型/API Key）
    public LlmPlanner(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public Plan plan(Goal goal) {
        PlanDto dto = callLlmForPlan(
                "用户目标：" + goal.description() +
                "\n请拆解为不超过 " + goal.maxSteps() + " 个步骤。");
        return toPlan(goal, dto);
    }

    @Override
    public Plan replan(Goal goal, String reason, Plan previous) {
        String context = """
            原始目标：%s
            上一版计划：%s
            失败/需重规划的原因：%s
            请在保留已成功步骤成果的前提下，重新规划剩余部分。只输出 JSON。
            """.formatted(goal.description(), previous.prettyPrint(), reason);
        PlanDto dto = callLlmForPlan(context);
        return toPlan(goal, dto);
    }

    /** 调 LLM 并解析为 DTO，带一次容错重试。 */
    private PlanDto callLlmForPlan(String userMessage) {
        try {
            return chatClient.prompt()
                    .system(PLAN_SYSTEM_PROMPT)
                    .user(userMessage)
                    .call()
                    .entity(PlanDto.class);   // Spring AI 结构化输出：自动把 JSON 映射为 record
        } catch (Exception e) {
            // 容错：解析失败时，追加更严格的指令重试一次
            return chatClient.prompt()
                    .system(PLAN_SYSTEM_PROMPT + "\n务必输出严格合法的 JSON，禁止任何多余字符。")
                    .user(userMessage)
                    .call()
                    .entity(PlanDto.class);
        }
    }

    /** 把不可信的 DTO 映射为可信的领域模型 Plan，同时做校验/纠正。 */
    private Plan toPlan(Goal goal, PlanDto dto) {
        List<PlanStep> steps = new ArrayList<>();
        if (dto != null && dto.steps() != null) {
            for (PlanDto.StepDto s : dto.steps()) {
                steps.add(new PlanStep(
                        safeId(s.id(), steps.size()),
                        s.description() == null ? "（未命名步骤）" : s.description(),
                        s.dependsOn() == null ? List.of() : s.dependsOn(),
                        parsePriority(s.priority()),
                        s.suggestedTool()
                ));
            }
        }
        if (steps.isEmpty()) {
            // 兜底：LLM 完全没给出步骤时，至少放一个「直接尝试完成」的步骤，避免空计划
            steps.add(new PlanStep("step-1", goal.description(),
                    List.of(), Priority.HIGH, null));
        }
        return new Plan("plan-" + UUID.randomUUID(), goal.id(), steps);
    }

    private String safeId(String id, int idx) {
        return (id == null || id.isBlank()) ? "step-" + (idx + 1) : id;
    }

    private Priority parsePriority(String p) {
        if (p == null) return Priority.MEDIUM;
        try { return Priority.valueOf(p.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return Priority.MEDIUM; }
    }

    private static final String PLAN_SYSTEM_PROMPT = """
        你是一个任务规划专家。请把用户目标拆解为一组可执行的步骤，并只返回 JSON。
        规则：
        1. 每个步骤要「小而具体、可被单一工具执行」。
        2. 用 dependsOn 表达步骤间的执行依赖（前置步骤的 id 列表），无依赖则为空数组。
        3. priority 从 [LOW, MEDIUM, HIGH, CRITICAL] 中选。
        4. suggestedTool 从可用工具里选：[browser, extract, summary, markdown]，不确定填 null。
        5. 步骤 id 用 "step-1"、"step-2" 递增。
        6. 只输出 JSON，不要任何解释文字、不要 markdown 代码块围栏。
        JSON 结构：
        { "steps": [ { "id": "...", "description": "...", "dependsOn": [], "priority": "HIGH", "suggestedTool": "browser" } ] }
        """;
}
```

**代码讲解要点**：
- `.entity(PlanDto.class)` 是 Spring AI 的结构化输出能力：它会自动在 Prompt 里注入 JSON Schema 提示，并把返回文本反序列化为你的 record。你几乎不用手写 JSON 解析。
- `callLlmForPlan` 带一次**容错重试**：第一次解析失败（LLM 偶尔会输出多余文字），换更严格的 system 提示再试一次。这是生产级必备。
- `toPlan` 是**防腐层**：对 LLM 返回的每个字段做空值兜底、优先级纠正、id 补齐、空计划兜底。永远不要假设 LLM 输出是完美的。

---

## 第三部分：怎么用（修正教学陷阱 + 写个 main 跑起来）

### 3.1 修正 PlanStep 的 final 陷阱

前面 `retryWith` 想修改 `description`，但字段是 `final`，编译不过。**修正方案**：把 `description` 改为非 final，并额外保留一个原始描述便于追溯。

```java
    private String description;              // 去掉 final，允许重试时追加提示
    private final String originalDescription;// 保留原始描述用于追溯

    // 构造器里：this.originalDescription = description;
```

**避坑教训**：可变对象里，哪些字段该 final、哪些不该，取决于「它是否随生命周期变化」。id、依赖、优先级是「身份属性」应 final；status、result、description（可被修正）是「状态属性」不 final。想清楚这个区分，模型就干净。

### 3.2 用一个临时 main / 测试跑通规划

```java
// 可写成一个 @SpringBootTest 或临时 CommandLineRunner 验证
Goal goal = Goal.of("分析 GitHub Trending 上最热门的 AI Agent 项目，并生成 Markdown 总结");
Plan plan = llmPlanner.plan(goal);
System.out.println(plan.prettyPrint());
```

预期输出（示意）：

```
Plan[plan-xxxx]
  step-1[打开 GitHub Trending 页面] deps=[] pri=HIGH tool=browser status=PENDING
  step-2[提取 Top5 AI Agent 项目信息] deps=[step-1] pri=HIGH tool=extract status=PENDING
  step-3[总结项目要点] deps=[step-2] pri=MEDIUM tool=summary status=PENDING
  step-4[生成 Markdown 报告] deps=[step-3] pri=MEDIUM tool=markdown status=PENDING
```

看到这个输出，说明「大脑」已经会拆解目标了——这是 Planning Agent 的第一个里程碑。

### 3.3 Spring AI 配置（application.yml）

```yaml
spring:
  ai:
    openai:
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.2      # 规划要稳定，温度调低
zero:
  planning:
    max-steps: 20
    max-replan: 3
    timeout-ms: 120000
```

**为什么 temperature 调低**：规划要求「稳定、可复现」，高温度会让 LLM 每次拆得不一样，不利于调试和生产可控。质检/反思可适当高一点以获得多样视角，但规划要低。

---

## 第四部分：用在哪（LlmPlanner 的真实变体）

| 场景 | Planner 变体 | 关键差异 |
|------|-------------|---------|
| 通用任务拆解 | LlmPlanner（本章） | 纯 LLM 拆解，最灵活 |
| 固定流程（如审批） | TemplatePlanner | 用模板/规则出计划，稳定不烧钱 |
| 高价值复杂任务 | TreeSearchPlanner | 生成多候选计划择优（chapter-08） |
| 混合 | HybridPlanner | 已知部分用模板，未知部分交 LLM |

**企业实践**：绝大多数生产 Agent 是 **Hybrid**——能写死的流程绝不交给 LLM（省钱、稳定、可控），只有真正需要「随机应变」的部分才用 LLM 规划。本课程先讲纯 LlmPlanner 打基础，你理解后自然能组合出 Hybrid。

---

## 第五部分：避坑指南

1. **别直接把 LLM 返回的 JSON 塞进领域模型**。必须过防腐层（toPlan）做校验兜底，否则 null、非法优先级、空计划会在下游炸开。

2. **别不做容错重试**。LLM 偶尔输出带围栏或多余文字的「脏 JSON」，一次解析失败就整单失败，用户体验极差。至少重试一次。

3. **别把 temperature 设高**。规划要稳定可复现，高温度会让计划飘忽不定，调试噩梦。

4. **别把 DTO 和领域模型合成一个类**。合并看似省事，实则让「外部不可信数据」和「内部可信对象」耦合，防腐层就没了。

5. **别忘了空计划兜底**。LLM 可能返回空 steps，必须兜底放一个步骤，否则主循环直接判定「无事可做」而误报成功。

6. **别把 Prompt 里的可用工具列表写死后忘了同步**。system prompt 里列的工具必须和真实 ToolRegistry（第六章）一致，否则 LLM 会建议不存在的工具。

7. **别混淆「身份字段」和「状态字段」的 final**。id/依赖 final，status/result 不 final，想清楚再定。

8. **别在 Planner 里调用工具**。Planner 只动脑。见过有人在规划阶段就去爬网页「看看再规划」，这违反职责分离，且让规划变慢变贵。

---

## 本章小结

我们完成了 `planner-core`：用 record 表达不可变的 `Goal`/`StepResult`，用充血类表达可变的 `PlanStep`/`Plan`，并实现了基于 Spring AI `ChatClient` 的 `LlmPlanner`——它能把一句自然语言目标稳定拆解为结构化计划，且带 DTO 防腐层和容错重试。

**核心记忆**：领域模型先行、值对象用 record、可变对象充血、LLM 输出必过防腐层、规划低温度、必带容错。

**下一章预告**：chapter-04 实现 `PlanningContext`（黑板模式）与带**转移校验的 `PlanState` 状态机**，把「计划、状态、观察、预算」统一管理起来，为主循环打地基。

---

## FAQ

**Q1：Spring AI 的 `.entity()` 底层怎么保证 LLM 输出合法 JSON？**
它会自动在 Prompt 后追加「输出格式说明 + JSON Schema」，并对返回文本做 JSON 反序列化。但它不保证 100% 成功，所以我们仍需容错重试 + 防腐层。

**Q2：为什么不用 LangChain4j 而用 Spring AI？**
两者都可以。本课程主线用 Spring AI（与 Spring Boot 生态无缝），LangChain4j 作为可选替代。核心思想（结构化输出、防腐层）与框架无关。

**Q3：replan 时怎么保留已成功步骤？**
本章 replan 先做「整体重规划」的最简版。第七章会升级为「携带 Context 里已 DONE 步骤的成果，只重规划剩余」的增量版。

---

## 面试高频题

1. 为什么领域模型要区分值对象（record）和实体/充血对象？举例说明。
2. 什么是防腐层（ACL）？在 LlmPlanner 里它体现在哪？
3. 让 LLM 稳定输出结构化数据有哪些工程手段？
4. 为什么规划阶段 temperature 要调低？
5. Planner 为什么不能调用工具？违反了什么原则？

---

## 扩展阅读

- 《领域驱动设计》（Eric Evans）——值对象、实体、防腐层
- Spring AI 官方文档：Structured Output / ChatClient
- 《Clean Architecture》（Robert C. Martin）——依赖方向与边界
- OpenAI Function Calling / JSON Mode 文档——结构化输出的模型侧原理