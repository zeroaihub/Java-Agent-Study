# 第二章：Planning 核心概念与状态机

> 本章是整个 Day10 的「概念地基」。我们把 Planning 世界里的每一个术语——Goal、Plan、SubTask、Task Tree、Task Graph、Task Queue、Execution Plan、Planner、Executor、Reflection、Observation、Reasoning、Self-Correction、Re-Planning、Tree Search、Task Dependency、Priority、Scheduler、状态机、生命周期——全部讲透，配 ASCII 图，讲底层实现、讲 Java 如何落地、讲 Spring AI 如何集成、讲为什么这样设计。
> 本章仍以「概念 + 设计思路」为主，真正的可运行代码从第三章开始逐模块落地；但本章会给出所有核心类的 Java 骨架，让你先建立「代码长什么样」的直觉。

---

## 第一部分：为什么学（核心价值）

### 1. 为什么必须先把概念讲清楚？

Planning Agent 是 Day10 里代码最多、模块最多的一天。如果你不先把「Goal 到底是什么、和 Plan 什么关系、SubTask 和 Step 是不是一回事、Task Tree 和 Task Graph 有什么区别」这些概念分清楚，后面写代码时你会陷入「命名混乱、职责不清、越写越乱」的泥潭。

**企业里 90% 的 Agent 代码烂账，都源于概念没对齐。** 比如有人把「计划」和「任务队列」混用，导致重规划时不知道该改哪个；有人把「反思」写进「执行器」里，导致质检逻辑和业务逻辑耦合得无法测试。所以本章的价值是：**先立规矩，再写代码。**

### 2. 为什么 ChatBot 用不到这些概念？

ChatBot 只有「消息（Message）」这一个概念。它没有 Goal（只有 question）、没有 Plan（不需要拆）、没有状态机（一问一答就结束）。而 Planning Agent 的这套概念，本质是为了描述「一个长期、多步、可失败、可恢复的任务的完整生命周期」——这是 ChatBot 根本不具备的复杂度。

### 3. 为什么企业级 Agent 必须有「状态机」？

一个 Planning 任务可能跑几分钟到几小时，中途可能：宕机、超时、等人审批、反思后推翻重来。如果用「一串顺序执行的代码」来写，你根本无法回答这些问题：

- 现在跑到哪一步了？（无法查询状态）
- 宕机了怎么从中间恢复？（无法持久化进度）
- 反思后要跳回规划阶段，代码怎么跳？（goto 是灾难）

**状态机是描述「长流程、可暂停、可恢复、可回溯」任务的唯一工程化答案。** 这也是 Temporal、Airflow、LangGraph 等所有编排系统的共同底座。

---

## 第二部分：是什么（概念全解 + ASCII 图 + Java 骨架）

我们按「从大到小、从静态到动态」的顺序讲。

### 2.1 概念层次总览

```
   Goal（目标：我要什么）
    │  拆解（decompose）
    ▼
   Plan（计划：一组有序/有依赖的步骤）
    │  由多个组成
    ▼
   PlanStep / SubTask（子任务：一件具体能执行的小事）
    │  执行时
    ▼
   Action（动作：调哪个工具、传什么参数）
    │  产生
    ▼
   Observation（观察：执行后看到的结果）
    │  反思
    ▼
   Reflection（反思：这一步做对了吗？要继续/重试/重规划/终止？）
```

一句话记忆链：**Goal 拆成 Plan，Plan 由 Step 组成，Step 执行产生 Action，Action 得到 Observation，Observation 触发 Reflection。**

### 2.2 Goal（目标）

**定义**：用户给 Agent 的「最终想要达成的结果」，通常是一句自然语言。它是整个 Planning 的起点和「终止判据」——只有 Goal 达成了，任务才算完成。

**关键点**：Goal ≠ Task。Goal 是「想要什么」（What），Task 是「怎么做」（How）。Goal 是模糊的、高层的；Task 是具体的、可执行的。

**Java 骨架**：

```java
public record Goal(
        String id,          // 唯一标识
        String description, // 自然语言目标，如「分析 GitHub Trending 最热 AI 项目并总结」
        int maxSteps,       // 预算：最多执行多少步（防无限循环）
        int maxReplan,      // 预算：最多重规划几次（防死循环）
        long timeoutMs      // 预算：整体超时
) {}
```

**为什么把预算放进 Goal**：因为「这个任务允许花多大代价」是任务的固有属性，应该随 Goal 一起传递，而不是散落在各处。

### 2.3 Plan（计划）与 PlanStep（步骤）

**Plan 定义**：为达成 Goal 而制定的「一组步骤的集合」，是 Planner 的产出物。它是**静态的蓝图**（还没执行）。

**PlanStep 定义**：Plan 的最小组成单元，描述「一件具体要做的事」。它包含：这一步要干什么、依赖哪些前置步骤、优先级、期望用什么工具。

**ASCII：一个 Plan 长什么样**

```
Goal: 分析 GitHub Trending 最热 AI 项目并总结
Plan:
 ┌── step-1  [打开并浏览 GitHub Trending 页面]  deps=[]      pri=HIGH  tool=browser
 ├── step-2  [从页面提取 Top5 项目名/描述/star]  deps=[step-1] pri=HIGH  tool=extract
 ├── step-3  [对提取内容做要点总结]              deps=[step-2] pri=MED   tool=summary
 └── step-4  [排版生成最终 Markdown]             deps=[step-3] pri=MED   tool=markdown
```

**Java 骨架**：

```java
public enum StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }
public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }

public class PlanStep {
    private final String id;              // 如 "step-1"
    private final String description;     // 这一步要做什么（自然语言）
    private final List<String> dependsOn; // 依赖的前置步骤 id
    private final Priority priority;       // 优先级
    private String suggestedTool;          // Planner 建议的工具（可为空，交给 ToolSelector 定夺）
    private StepStatus status = StepStatus.PENDING;
    private String result;                 // 执行后的观察结果
    // getters/setters/构造器省略处，实现见 chapter-03
}

public class Plan {
    private final String id;
    private final String goalId;
    private final List<PlanStep> steps;   // 步骤列表（含依赖，逻辑上是 DAG）
    // ...
}
```

**为什么 Plan 用 List 而不是直接用 Map/Tree**：因为 Plan 首先是「有序的可读列表」（方便人和 LLM 阅读），依赖关系用 `dependsOn` 字段表达，运行时再由 Scheduler 构建成 Graph。**存储用 List，调度用 Graph**——这是关注点分离。

### 2.4 SubTask vs PlanStep：是不是一回事？

这是最容易混淆的点。在**本项目的语境**里，我们统一：

- **PlanStep = SubTask**：都指「Plan 里的一个可执行子任务」。业界两个词经常混用，我们代码里统一叫 `PlanStep`，文档里为了呼应「目标拆解」有时叫 SubTask，二者等价。
- 区别只在语气：讲「拆解」时叫 SubTask（强调它是从 Goal 拆出来的），讲「执行/调度」时叫 Step（强调它是流程里的一步）。

**为什么要说清楚**：避免你在代码里同时出现 `SubTask` 和 `PlanStep` 两个类，造成重复和转换成本。**一个概念，一个类。**

### 2.5 Task Tree（任务树）vs Task Graph（任务图）

**Task Tree（任务树）**：目标拆解的**层级结构**——大目标拆成中目标，中目标再拆成小任务。它表达的是「拆解的父子关系」。

```
                 [分析并总结 GitHub Trending]        ← 根目标
                    │
       ┌────────────┼────────────┐
       ▼            ▼            ▼
   [获取数据]    [分析数据]     [产出报告]              ← 中层子目标
       │            │            │
   ┌───┴───┐        │        ┌───┴───┐
   ▼       ▼        ▼        ▼       ▼
 [浏览]  [提取]   [总结]   [排版]  [校验]              ← 叶子任务（可执行）
```

**Task Graph（任务图 / DAG）**：任务之间的**依赖关系**——谁必须在谁之前完成。它是有向无环图。

```
 [浏览]───▶[提取]───▶[总结]───▶[排版]───▶[校验]
   (step1)   (step2)   (step3)   (step4)   (step5)
   ▲ 边表示「依赖」：提取依赖浏览完成，总结依赖提取完成……
```

**区别**：Tree 表达「拆解层级」（who is part of who），Graph 表达「执行依赖」（who runs before who）。**Tree 是给人和 LLM 看的拆解逻辑，Graph 是给调度器用的执行约束。** 本项目中，Planner 产出的 Plan（带 dependsOn）本质就承载了 Graph 信息，Scheduler 会在运行时把它「解读」成 Graph 来调度。

### 2.6 Task Queue（任务队列）与 Execution Plan（执行计划）

**Task Queue（任务队列）**：所有「已就绪、等待执行」的步骤排成的队列。Scheduler 每轮从 Graph 里挑出「依赖已满足」的步骤放进 Queue，再按优先级取出执行。

**Execution Plan（执行计划）**：Plan（静态蓝图）+ 运行时状态（每步现在是 PENDING/RUNNING/DONE）+ 观察结果，合起来就是「执行计划」。它是**动态的**，随执行不断变化。

```
静态 Plan（蓝图）    +    运行时状态/观察     =    Execution Plan（动态执行态）
[step1,2,3,4]           step1=DONE(结果X)         知道做到哪、每步结果是什么
                        step2=RUNNING              → 可持久化、可恢复、可观测
                        step3,4=PENDING
```

在我们的实现里，**这个「动态执行态」由 `PlanningContext`（黑板）承载**——第四章详解。

### 2.7 Planner（规划器）与 Executor（执行器）

这是 Planning Agent 最核心的「两个角色」，必须严格分离：

- **Planner（规划器）**：输入 Goal（+历史上下文），输出 Plan。它只「动脑」，不「动手」。核心方法 `plan(goal)` 和 `replan(context)`。
- **Executor（执行器）**：输入一个 PlanStep，调用工具执行，输出 StepResult。它只「动手」，不「动脑」（不决定下一步做什么）。

**ASCII：Planner与 Executor 的分工**

```
   Goal ──▶ [Planner 动脑] ──▶ Plan ──▶ [Scheduler 挑步] ──▶ Step
                 ▲                                            │
                 │ replan（反思要求改计划时）                   ▼
                 │                                     [Executor 动手]
                 │                                            │
                 └──────── context（观察结果反馈）◀────── StepResult
```

**为什么必须分离**：
1. **可测试**：Planner 可以用假 LLM 测「拆解逻辑」，Executor 可以用假工具测「执行逻辑」，互不干扰。
2. **可替换**：想升级规划能力，只换 Planner（如从 GPT-4 换成更强模型），Executor 不动。
3. **可复用**：同一个 Executor 能执行任何 Planner 产出的计划。
4. **职责清晰**：这是软件工程「单一职责原则」在 Agent 领域的直接体现。

**Java 骨架**：

```java
public interface Planner {
    Plan plan(Goal goal, PlanningContext ctx);   // 首次规划
    Plan replan(Goal goal, PlanningContext ctx);  // 重规划（带已有上下文）
}

public interface Executor {
    StepResult execute(PlanStep step, PlanningContext ctx);
}
```

### 2.8 Reasoning / Action / Observation（ReAct 三元组）

这是执行循环的「心跳」，源自 ReAct 论文：

- **Reasoning（推理 / Thought）**：LLM 想「这一步我该干什么、为什么」。
- **Action（动作）**：具体行动——调用哪个工具、传什么参数。
- **Observation（观察）**：执行 Action 后得到的结果（工具返回值 / 错误信息）。

```
   Thought:  「我需要先打开 GitHub Trending 页面才能看到项目」
      │
      ▼
   Action:   browser.open(url="https://github.com/trending")
      │
      ▼
   Observation: 「页面 HTML，含 25 个 trending 仓库……」
      │
      ▼（进入下一轮 Thought 或反思）
```

**为什么要把这三个记全**：这三元组是 Agent 的「思维日志」。全部落库后，线上出问题，你能完整还原「Agent 当时怎么想、做了什么、看到什么」——这是可观测性的核心。

### 2.9 Reflection（反思）与 Self-Correction（自我修正）

- **Reflection（反思）**：执行一步（或若干步）后，独立地审视「结果对不对、够不够好、要不要改」。它是一次额外的 LLM 调用（或规则判断），产出一个「裁决」。
- **Self-Correction（自我修正）**：反思发现问题后，Agent **自己**调整——可能是「换个参数重试这一步」，也可能是「推翻计划重新规划」。

反思的四种裁决（本项目定义）：

```
 SUCCESS     → 这步/整体 OK，继续下一步或结束
 RETRY_STEP  → 这步没做好，用建议的新参数重试同一步（Self-Correction 的一种）
 REPLAN      → 计划本身有问题，需要重新规划（Re-Planning）
 ABORT       → 无法完成，优雅终止并上报
```

**为什么反思要独立于执行**：见第一章——把「做」和「判断做得对不对」解耦，才能用不同 Prompt / 不同模型做质检，也才能给反思单独设次数上限。

### 2.10 Re-Planning（重规划）

**定义**：反思判定 `REPLAN` 时，Planner 带着「已完成的成果 + 失败原因 + 已有观察」重新生成计划。

**关键：重规划不是从零重来**，而是**增量修正**——保留已成功的步骤成果，只重新规划「还没做好 / 剩余」的部分。这既省 Token，又不会推翻已经对的部分。

```
 原计划: [1✓][2✓][3✗失败][4][5]
                    │ REPLAN（带 1、2 的成果 + 3 的失败原因）
                    ▼
 新计划: [1✓][2✓][3'新方案][3.5'补救步骤][4][5]   ← 只改 3 及之后
```

### 2.11 Tree Search（树搜索）——进阶概念

前面的规划是「线性的」（一条计划走到底）。更强的规划会像 Tree of Thoughts 那样，**同时探索多条计划路径，择优前进**：

```
              Goal
             /  |  \
        方案A  方案B  方案C        ← 生成多个候选计划
          |     |      |
        评估   评估    评估         ← 给每条打分（LLM 或规则）
          └─────┴──────┘
                │ 选最优（或回溯换路）
                ▼
             执行方案B
```

本项目**默认用线性规划 + 反思重规划**（工程上够用、可控、便宜），Tree Search 作为 chapter-08 的进阶扩展点介绍——因为它 Token 成本高，企业里通常只在「高价值、可容忍高成本」的任务上启用。

### 2.12 Task Dependency（依赖）与 Priority（优先级）

- **Dependency（依赖）**：`step3.dependsOn = [step2]` 意味着 step2 没完成，step3 不能开始。它决定「执行顺序的硬约束」。
- **Priority（优先级）**：当多个步骤同时就绪（依赖都满足）时，先执行谁。它决定「就绪步骤之间的软排序」。

```
 就绪判定：deps 全部 DONE  → 进入就绪集合
 就绪集合内：按 Priority 从高到低取（CRITICAL > HIGH > MEDIUM > LOW）
```

### 2.13 Scheduler（调度器）

**定义**：负责在运行时「按依赖 + 优先级，决定下一个执行哪一步」的组件。它是连接 Plan（静态）和 Executor（执行）的「交通指挥」。

```java
public interface Scheduler {
    // 返回下一个该执行的步骤；没有就绪步骤时返回 empty
    Optional<PlanStep> nextStep(Plan plan, PlanningContext ctx);
    // 是否全部完成
    boolean isAllDone(Plan plan);
}
```

**为什么要独立 Scheduler**：把「挑哪一步」的逻辑独立出来，将来支持「并行调度（一次挑多个就绪步骤）」时，只改 Scheduler，不动 Executor 和 Planner。

### 2.14 状态机（State Machine）与生命周期

我们在 ARCHITECTURE.md 已画过状态机。这里给出 Java 落地骨架和「为什么每个转移这样设计」：

```java
public enum PlanState {
    NEW,            // 刚收到目标
    PLANNING,       // 正在规划
    READY,        // 计划就绪
    EXECUTING,      // 正在执行某步
    REFLECTING,     // 正在反思
    RE_PLANNING,    // 正在重规划
    WAITING_HUMAN,  // 等待人工审批（Day11）
    SUCCEEDED,      // 成功（终态）
    FAILED          // 失败（终态）
}
```

合法转移（**用代码强制约束，禁止非法跳转**）：

```
 NEW        → PLANNING
 PLANNING   → READY | FAILED
 READY      → EXECUTING
 EXECUTING  → REFLECTING | FAILED
 REFLECTING → EXECUTING(继续下一步) | RE_PLANNING | WAITING_HUMAN | SUCCEEDED | FAILED
 RE_PLANNING→ READY | FAILED
 WAITING_HUMAN → EXECUTING | FAILED
```

**为什么用枚举 + 显式转移方法，而不是随便改一个 String 字段**：因为「状态非法跳转」是 Agent 最隐蔽的 bug 来源（比如从 SUCCEEDED 又跳回 EXECUTING）。用枚举 + 校验转移，能在编译期和运行期都挡住非法状态流转，这是企业级健壮性的体现。第四章会实现带转移校验的 `PlanState`。

---

## 第三部分：怎么用（概念如何组装成一台「规划机」）

前面讲的都是「零件」。现在把它们组装成一台能自转的机器。整个 Planning Agent 的运转，就是**状态机驱动下的一个大循环**：

```
 收到 Goal
   │
   ▼ NEW → PLANNING
 [Planner] 拆解 Goal → 生成 Plan（写入 PlanningContext）
   │
   ▼ PLANNING → READY → EXECUTING
 ┌─────────────── 主循环（每轮一步）───────────────┐
 │  [Scheduler] 按依赖+优先级挑出 nextStep          │
 │      │  没有就绪步骤且全 DONE → SUCCEEDED         │
 │      ▼                                          │
 │  [ToolSelector] 为这一步选定工具                 │
 │      │                                          │
 │      ▼                                          │
 │  [Executor] 调工具执行 → StepResult（观察）       │
 │      │  写回 PlanningContext                     │
 │      ▼ EXECUTING → REFLECTING                    │
 │  [Reflector] 反思 → 裁决                          │
 │      ├─ SUCCESS    → 标记 DONE，回循环顶继续下一步 │
 │      ├─ RETRY_STEP → 用新参数重试同一步            │
 │      ├─ REPLAN     → RE_PLANNING（回 Planner）    │
 │      └─ ABORT      → FAILED                       │
 │  预算护栏：超 maxSteps/maxReplan/timeout → FAILED  │
 └──────────────────────────────────────────────────┘
   │
   ▼ 输出最终结果（Markdown）
```

**组装的四条黄金规则**：

1. **所有状态数据只放在 PlanningContext（黑板）里**，各组件都从黑板读、往黑板写，彼此不直接调用——这叫黑板模式，是解耦的关键（第四章展开）。
2. **每一轮循环只推进一步**，且每步执行后必反思。不允许「一口气跑完再统一检查」——那样错误会累积、难定位。
3. **每一次状态转移都走 PlanState 的校验方法**，禁止直接赋值状态字段。
4. **每一轮循环开始前先检查预算护栏**（步数、重规划次数、总时长），超限立即 FAILED——这是防止「烧钱失控」的保险丝。

**Spring AI 如何嵌进来**：Planner 和 Reflector 内部通过注入的 `ChatClient`（Spring AI 的核心客户端）调用 LLM。也就是说，「动脑」的组件依赖 Spring AI，「动手/调度」的组件（Executor、Scheduler）不依赖 LLM。这条边界，第三、五、六章会反复用到。

```java
// 主循环的伪代码骨架（真实实现见 chapter-07 PlanningService）
PlanningContext ctx = new PlanningContext(goal);
ctx.transitionTo(PlanState.PLANNING);
Plan plan = planner.plan(goal, ctx);
ctx.setPlan(plan);
ctx.transitionTo(PlanState.READY);

while (!scheduler.isAllDone(plan)) {
    ctx.guardBudgetOrThrow();               // 预算护栏
    Optional<PlanStep> next = scheduler.nextStep(plan, ctx);
    if (next.isEmpty()) break;                // 无就绪步骤
    PlanStep step = next.get();

    ctx.transitionTo(PlanState.EXECUTING);
    ToolSpec tool = toolSelector.select(step, ctx);
    StepResult result = executor.execute(step, ctx); // 内部用选中的 tool
    ctx.record(step, result);

    ctx.transitionTo(PlanState.REFLECTING);
    ReflectionResult r = reflector.reflect(step, result, ctx);
    switch (r.verdict()) {
        case SUCCESS    -> step.markDone();
        case RETRY_STEP -> step.retryWith(r.suggestion());
        case REPLAN     -> { ctx.transitionTo(PlanState.RE_PLANNING);
                             plan = planner.replan(goal, ctx);
                             ctx.setPlan(plan);
                             ctx.transitionTo(PlanState.READY); }
        case ABORT      -> { ctx.transitionTo(PlanState.FAILED); return ctx.fail(); }
    }
}
ctx.transitionTo(PlanState.SUCCEEDED);
return ctx.result();
```

---

## 第四部分：用在哪（真实业务场景对号入座）

把本章概念映射到真实企业场景，你才知道每个概念「不是学术玩具」：

| 概念 | 真实业务场景 | 不用它会怎样 |
|------|-------------|-------------|
| Goal + 预算 | 客服工单「帮我退款并通知用户」，限 10 步内、30 秒超时 | 无预算 → Agent 死循环烧 Token，账单爆炸 |
| Plan / PlanStep | 「生成季度财报」拆成：取数→清洗→计算→出图→成文 | 不拆 → LLM 一次做完，中途错无法定位 |
| Task Graph 依赖 | 「先付款成功才能发货」——发货 dependsOn 付款 | 无依赖 → 并发乱序，未付款先发货 |
| Priority | 风控场景「先冻结账户，再慢慢查流水」 | 无优先级 → 高危动作被低价值任务挤后 |
| Scheduler | 数据管道并行调度多个无依赖的抽取任务 | 无调度 → 只能串行，慢 |
| Reflection | 生成的 SQL 先校验语法/权限再执行 | 无反思 → 错误 SQL 直接打到生产库 |
| Re-Planning | 爬取网站被反爬拦截，改用 API 方案 | 无重规划 → 一条路走到黑，任务失败 |
| 状态机 | 长审批流程宕机后从中断处恢复 | 无状态机 → 宕机=从头再来，用户抓狂 |
| Human-in-the-loop | 转账 >5万 必须人工复核（Day11） | 无审批 → 高危操作无人兜底 |

**本项目的落地场景**：Day10 的最终 Demo——「分析 GitHub Trending 最热 AI Agent 项目并生成 Markdown」，恰好把 Goal、Plan、依赖、工具选择、执行、反思、失败重规划、Markdown 产出这条链路全部串起来。它是一个「麻雀虽小五脏俱全」的企业级 Planning 缩影。

---

## 第五部分：避坑指南（10 条概念级血泪教训）

1. **别把 SubTask 和 PlanStep 建成两个类**。一个概念一个类，否则到处是转换代码。本项目统一用 `PlanStep`。

2. **别把「拆解层级(Tree)」和「执行依赖(Graph)」混为一谈**。Tree 给人看拆解逻辑，Graph 给调度器排顺序。存储用 List+dependsOn，运行时才构建 Graph。

3. **别让 Planner 去执行、别让 Executor 去决策**。动脑和动手必须分家，否则无法单元测试、无法替换模型。

4. **别用 String 字段存状态**。必须用枚举 + 转移校验方法。见过太多「状态被某处代码悄悄改成非法值」导致的诡异线上问题。

5. **别忘了预算护栏**。maxSteps / maxReplan / timeout 三道保险丝一个都不能少，否则 LLM 会陪你烧钱到天亮。

6. **别「跑完全部再统一反思」**。每步必反思，错误就地拦截。累积式检查会让你无法定位是哪一步坏的。

7. **重规划别从零重来**。要带上已成功步骤的成果做增量修正，否则既费 Token 又可能推翻已经对的部分。

8. **别把观察结果(Observation)只打日志不落库**。Reasoning/Action/Observation 三元组要完整持久化到 Context，这是唯一能事后复盘的依据。

9. **别让各组件互相直接 new / 直接调用**。所有共享数据走 PlanningContext 黑板，组件之间零耦合，才能独立替换和测试。

10. **别把 Tree Search 当默认方案**。它 Token 成本高，企业里默认用「线性规划 + 反思重规划」就够，只在高价值任务上才启用树搜索。

---

## 本章小结

本章我们建立了 Planning Agent 的完整概念地图：从 **Goal → Plan → PlanStep → Action → Observation → Reflection** 这条主链，到 **Tree（拆解）vs Graph（依赖）** 的区分，再到 **Planner/Executor/Scheduler/Reflector 四大角色** 的职责分离，最后落到 **状态机 + 大循环 + 预算护栏** 的运转机制。

**核心记忆**：Planning Agent = 状态机驱动的「规划-调度-执行-反思-重规划」大循环，所有数据走黑板，所有转移走校验，所有资源受护栏约束。

**下一章预告**：chapter-03 我们正式开写代码——搭建 `planner-core` 领域模型（Goal / Plan / PlanStep / 枚举），并实现基于 Spring AI 的 `LlmPlanner`，让 Agent 真正「会拆解目标」。

---

## FAQ

**Q1：一个 Goal 能对应多个 Plan 吗？**
能。首次规划出一个 Plan，重规划会产出新的 Plan。同一时刻只有一个「当前生效的 Plan」，历史 Plan 可留档用于追溯。

**Q2：Scheduler 一次只能挑一个步骤吗？**
本项目默认串行（一次一个），接口设计上预留了并行扩展。并行调度需处理「多步同时写黑板」的并发问题，属于进阶（chapter-08 讨论）。

**Q3：反思一定要调 LLM 吗？**
不一定。简单场景可用规则反思（如「结果为空/含报错关键字 → RETRY」），复杂场景才用 LLM 反思。本项目两种都会实现，可配置切换。

**Q4：状态机能不能直接用 Spring StateMachine 框架？**
可以，但对本项目属于「过重」。我们手写一个轻量枚举状态机，既能讲清原理，又零额外依赖。企业里状态复杂时再引入专业框架不迟。

---

## 面试高频题

1. 请解释 Task Tree 和 Task Graph 的区别，分别用在什么阶段？
2. 为什么 Planner 和 Executor 要严格分离？分离带来哪些工程收益？
3. Reflection 的四种裁决分别是什么？RETRY_STEP 和 REPLAN 有何本质区别？
4. 为什么重规划要做「增量修正」而不是「从零重来」？
5. 为什么状态必须用枚举 + 转移校验，而不能用 String 字段？
6. Planning Agent 里有哪三道预算护栏？各自防什么风险？

---

## 扩展阅读

- ReAct: Synergizing Reasoning and Acting in Language Models（Yao et al., 2022）
- Reflexion: Language Agents with Verbal Reinforcement Learning（Shinn et al., 2023）
- Tree of Thoughts: Deliberate Problem Solving with LLMs（Yao et al., 2023）
- Plan-and-Solve Prompting（Wang et al., 2023）
- 《Designing Data-Intensive Applications》——状态机与持久化章节
- Temporal / LangGraph 官方文档——工业级工作流状态机的参考实现