# 第二章 · Multi-Agent 底层架构

> Day08 · Multi-Agent 训练营 第二章学习记录
> 教学身份：大学教授 + 企业导师 + 架构师 + Pair Programming
> 本章目标：把 Coordinator / Planner / Executor / Reviewer / Memory / Communication 六大件的底层原理讲透，并给出完整流程图。学完你要能自己画出整套架构。

---

## 第一部分：为什么学（核心价值）

第一章我们想清楚了“为什么要多 Agent”。但“想上多 Agent”和“能设计出一套能跑、能维护、能扩展的多 Agent 架构”之间，隔着一整套底层机制。

企业里 90% 的 Multi-Agent 项目失败，不是败在“想不到要拆”，而是败在：
- 拆了之后**没人统一调度**（谁先干、谁后干、谁说了算），变成一团乱麻。
- Agent 之间**乱传数据**（A 直接 call B，B 又 call A），形成难以追踪的调用网。
- 没有**统一的执行契约**，每个 Agent 输入输出格式都不一样，粘不起来。
- 没有**质检角色**，产出无人负责。
- 没有**上下文承载体**，中间结果丢来丢去。

所以本章的价值是：**给你一套“可落地的骨架”**。理解了这套骨架，你不仅能写今天的项目，还能看懂 AutoGen 的 GroupChatManager、LangGraph 的 StateGraph、CrewAI 的 Crew——因为它们的内核是同一套东西。

---

## 第二部分：是什么（六大核心组件 + 底层原理）

我们逐个拆解，每个组件回答三个问题：它是什么？它解决什么问题？它的生命周期/数据是什么？

### 2.1 Coordinator（协调者 / 编排大脑）

- **是什么**：整个系统的“项目经理”。它接收用户任务，决定“调用哪些 Agent、按什么顺序、把谁的输出喂给谁、什么时候结束、如何汇总”。
- **解决什么问题**：解决“多 Agent 一盘散沙”的问题。有了中心化的 Coordinator，流程可见、可控、可审计。
- **两种主流形态**：
  - **静态编排**：顺序写死（Planner→Research→Writer→Reviewer）。简单、可预测。**本项目 V1 采用**。
  - **动态编排**：Coordinator 自己是一个 LLM，每一步“思考”下一步该调谁（Supervisor 模式）。灵活但更贵、更难控。
- **底层原理**：本质是一个“状态机 + 调度循环”。Coordinator 维护当前状态（做到哪一步了），每一轮从状态推导出“下一个要执行的 Agent”，执行完更新状态，直到到达终止状态。

### 2.2 Planner（规划者）

- **是什么**：把一个“大而模糊的目标”拆解成“小而明确的子任务序列”。比如把“写一篇 AI 工具文章”拆成大纲：`[开篇, 工具1介绍, 工具2介绍, 选型建议, 结语]`。
- **解决什么问题**：解决“任务太大，一步做不完、也做不好”的问题。拆解后每个子任务足够小，Executor 才能做好。
- **底层原理**：Planner 的输出是一个**结构化的计划（Plan）**，通常是有序列表或 DAG（有向无环图）。计划是后续所有 Agent 的“作战地图”。
- **常见坑**：Planner 拆得太细（Agent 太多、太慢）或太粗（Executor 又得二次拆解）。粒度是艺术，需按任务调。

### 2.3 Executor（执行者）

- **是什么**：真正“干活”的 Agent。它领取一个明确子任务，调用能力（LLM / 工具 / MCP）产出结果。
- **本项目细分**：为了体现分工，我们把 Executor 拆成 **ResearchAgent（找素材）** 和 **WriterAgent（写正文）**。
- **解决什么问题**：把“执行”与“规划/审校”解耦，Executor 只需专注把手上这件事做好。
- **底层原理**：Executor = 读上下文（从共享记忆拿 Planner 的计划）→ 组装 Prompt / 调用工具 → 产出 → 写回共享记忆。它是无状态的（状态都在共享记忆里），因此天然可并行、可水平扩展。

### 2.4 Reviewer（评审者）

- **是什么**：独立的“质检员”。它不生产，只审查：检查事实、逻辑、格式、完整度，给出**分数 + 修改意见**。
- **解决什么问题**：解决“自产自销、无人纠错”的问题。它与 Executor 构成“生成—审查”的对抗结构。
- **底层原理**：Reviewer 的输出是**结构化评审结果**（score + issues + suggestions）。Coordinator 依据 score 决定：通过（结束）还是打回（让 Writer 重写）。这就是“反思闭环”的判据。
- **为什么必须独立**：如果让 Writer 自己审自己，等于让学生给自己判卷——它的“盲点”正是它审不出来的地方。独立 Reviewer 有不同的 System Prompt（严格挑刺视角），才能看到 Writer 看不到的问题。

### 2.5 Shared Memory（共享记忆 / 黑板）

- **是什么**：一块所有 Agent 都能读写的“公共白板”。Planner 把大纲写上去，Research 把素材写上去，Writer 从上面读大纲和素材……
- **解决什么问题**：解决“Agent 之间如何传数据”的问题，且做到**解耦**（Writer 不需要知道素材来自哪个 Agent）和**可追溯**（黑板就是执行快照）。
- **底层原理**：本质是一个线程安全的 `Map<String, Object>`，配合“键约定”（如 `outline` / `materials` / `draft` / `review`）。企业级会升级为带版本、带权限、带持久化的存储。
- **隔离 vs 共享的边界**（第五章深讲）：不是所有东西都该共享。用户隐私、临时草稿可隔离；最终产物、公共上下文才共享。

### 2.6 Communication（通信）

- **是什么**：Agent 之间“说话”的方式。第一章 README 已列三种：直接消息、黑板、消息总线。
- **本项目选型**：以**黑板**为主（通过 SharedMemory），Coordinator 与 Agent 之间用**方法调用 + Message/Task 对象**传递。
- **底层原理**：无论哪种方式，通信的载体都需要一个**标准化的消息模型**（`Message`：谁发的、发给谁、什么类型、内容是什么、什么时候）。标准化是“粘合”多个异构 Agent 的前提。

### 2.7 Agent 生命周期与上下文

一个 Agent 从被调用到产出，经历如下生命周期（本项目在 `AbstractAgent` 里用模板方法固化）：

```
receive(ctx)          接收上下文（含 Task、SharedMemory 引用）
  → beforeExecute()   前置：记录开始时间、打日志
  → doExecute(ctx)    核心：子类实现的真正逻辑（读记忆→产出→写记忆）
  → afterExecute()    后置：记录耗时、产出摘要、写 AgentExecutionLog
  → return AgentResult 返回结构化结果
（任一步异常）→ onError() 统一兜底：记失败日志、返回失败结果，不让异常穿透 Coordinator
```

**AgentContext（上下文）** 是贯穿一次协作的“公文包”，装着：
- `task`：当前要完成的任务（含用户原始需求）；
- `memory`：共享记忆引用（Agent 读写中间结果的地方）；
- `logs`：本次协作的执行日志集合（可观测性）。

---

## 完整架构流程图（本章核心产出）

```
┌────────────────────────────────────────────────────────────────────┐
│                          用户 / Controller                          │
│                     "帮我写一篇 AI 工具推荐文章"                       │
└───────────────────────────────┬────────────────────────────────────┘
                                │ Task{topic, requirement}
                            ▼
┌────────────────────────────────────────────────────────────────────┐
│                          Coordinator（状态机 + 调度循环）             │
│   state: START → PLANNED → RESEARCHED → WRITTEN → REVIEWED → DONE     │
└───┬──────────────┬──────────────────┬──────────────────┬────────────┘
    │ ①分派         │ ②分派            │ ③分派            │ ④分派
    ▼              ▼                  ▼                  ▼
┌────────┐   ┌──────────┐       ┌──────────┐       ┌──────────┐
│Planner │   │ Research │       │  Writer  │       │ Reviewer │
│ Agent  │   │  Agent   │       │  Agent   │       │  Agent   │
└───┬────┘   └────┬─────┘       └────┬─────┘       └────┬─────┘
    │write        │read outline      │read outline      │read draft
    │outline │write materials   │+materials        │write review
    │             │                  │write draft       │+score
    ▼             ▼                  ▼                  ▼
┌────────────────────────────────────────────────────────────────────┐
│                       SharedMemory（黑板）                           │
│  { outline:[...], materials:{...}, draft:"...", review:"...",        │
│    score:0.9, logs:[AgentExecutionLog...] }                          │
└───────────────────────────────┬────────────────────────────────────┘
                          │ Coordinator 读 draft/score
                    score>=阈值 │              score<阈值(进阶:反思闭环)
                     ┌──────────┴──────────┐
                     ▼                     ▼
                  DONE 汇总            回退给 WriterAgent 重写
                     │                （受 maxRounds 保护）
                     ▼
             返回最终 Markdown + 执行日志
```

**消息流文字版**（务必背下来）：

```
1. Coordinator 收到 Task，state=START
2. 调 PlannerAgent  → 写 memory["outline"]        → state=PLANNED
3. 调 ResearchAgent → 读 outline，写 memory["materials"] → state=RESEARCHED
4. 调 WriterAgent   → 读 outline+materials，写 memory["draft"] → state=WRITTEN
5. 调 ReviewerAgent → 读 draft，写 memory["review"]、memory["score"] → state=REVIEWED
6. 判定 score：达标→state=DONE 汇总返回；不达标→(进阶)回到第4步，rounds++
7. rounds 达到 maxRounds 强制结束，防止死循环
```

---

## 第三部分：怎么用（本章为原理章，代码在第三章落地）

本章你只需在脑子里把上面的流程图“跑”一遍。第三章我们就把这张图翻译成 Java：
- `Coordinator` = 那个状态机 + 调度循环；
- `AgentRole` 枚举 = 图里四个方框的角色；
- `SharedMemory` = 中间那块黑板；
- `AgentContext` = 在箭头上流动的公文包；
- `AgentExecutionLog` = 黑板里 `logs` 那一项。

---

## 第四部分：用在哪（真实项目映射）

这套“Coordinator + 角色 Agent + 黑板”骨架，几乎适配所有企业场景，只是角色不同：

| 场景 | Planner | Executor | Reviewer |
|------|---------|----------|----------|
| AI 内容生产（本项目） | 定大纲 | Research+Writer | 审校打分 |
| AI 研发助手 | 需求拆解 | 编码 Agent | Code Review Agent |
| AI 法务 | 审查计划 | 条款抽取+风险识别 | 合规校验 Agent |
| AI 数据分析 | 分析计划 | 取数+清洗+分析 | 结论校验 Agent |

**你会发现：换场景只是换 Agent 实现，骨架（Coordinator/Memory/Context/Log）完全复用。** 这就是好架构的价值。

---

## 第五部分：避坑与优化（企业最佳实践预告）

本章底层架构相关的 4 个坑（第七章系统展开）：

1. **状态机必须有终止态**：没有 DONE / maxRounds，Coordinator 可能永远转下去。
2. **黑板键要有约定**：`outline`/`materials`/`draft` 等键名要统一定义（建议用常量类），否则 Agent 之间对不上暗号。
3. **异常不能穿透**：单个 Agent 挂了，不能让整个 Coordinator 崩溃。`AbstractAgent.onError()` 统一兜底，返回失败结果由 Coordinator 决策（重试/降级/跳过）。
4. **上下文不能无限膨胀**：黑板里的东西越攒越多，喂给 LLM 的 Prompt 会越来越长、越来越贵。要做“上下文裁剪”（只喂当前 Agent 需要的键）。

---

## 本章总结

- 六大件：Coordinator（调度大脑）、Planner（拆解）、Executor（干活）、Reviewer（质检）、SharedMemory（黑板）、Communication（标准消息）。
- Coordinator 本质是“状态机 + 调度循环”，必须有终止态。
- Agent 生命周期：before → doExecute → after，异常统一 onError 兜底。
- AgentContext 是流动的公文包，SharedMemory 是静止的黑板。
- 好架构的标志：换场景只换 Agent 实现，骨架完全复用。

## Java 代码（本章为原理章，给出 Coordinator 状态推进的“伪代码骨架”，正式实现见第三章）

```java
// 仅示意 Coordinator 的“状态机 + 调度循环”思想，完整实现见第三章 coordinator.Coordinator
public AgentResult run(Task task) {
    AgentContext ctx = new AgentContext(task, new SharedMemory());
    plannerAgent.execute(ctx);   // START    -> PLANNED
    researchAgent.execute(ctx);  // PLANNED   -> RESEARCHED
    writerAgent.execute(ctx);    // RESEARCHED-> WRITTEN
    reviewerAgent.execute(ctx);  // WRITTEN   -> REVIEWED
    return summarize(ctx);       // REVIEWED  -> DONE（进阶：score 不达标则回退重写）
}
```

## Python 参考（LangGraph 风格状态机）

```python
# LangGraph：用 StateGraph 把每个 Agent 声明为 node，用 edge 声明流转
from langgraph.graph import StateGraph, END

g = StateGraph(dict)  # dict 就是我们的 SharedMemory
g.add_node("plan", planner_node)
g.add_node("research", research_node)
g.add_node("write", writer_node)
g.add_node("review", reviewer_node)
g.add_edge("plan", "research")
g.add_edge("research", "write")
g.add_edge("write", "review")
# 条件边：审校达标就结束，否则回到 write（反思闭环）
g.add_conditional_edges("review", lambda s: END if s["score"] >= 0.8 else "write")
g.set_entry_point("plan")
app = g.compile()
```

> 对照：LangGraph 的 `StateGraph` = 我们的 Coordinator，`node` = Agent，`state(dict)` = SharedMemory，`conditional_edges` = 反思闭环判据。我们手写它，是为了看清框架内部的机器。

## 企业案例

- **AutoGen GroupChat**：GroupChatManager 就是 Coordinator，它按策略选下一个发言的 Agent，本质也是状态机调度。
- **MetaGPT**：用固定 SOP（标准作业流程）串起产品/架构/开发/测试，正是“静态编排 + 角色 Agent + 共享文档（黑板）”。

## 常见问题（FAQ）

- **Q：Coordinator 一定要中心化吗？去中心化行不行？**
  A：去中心化（Agent 互相直接调用）在小规模可行，但会形成难追踪的调用网、易死循环。企业绝大多数选中心化，为了可观测和可治理。
- **Q：SharedMemory 用数据库还是内存？**
  A：V1 用内存（`ConcurrentHashMap`），够快够简单。生产环境按需换 Redis/DB 以支持持久化、跨进程、回放。
- **Q：Reviewer 打回后，Writer 怎么知道要改哪里？**
  A：Reviewer 把 `issues/suggestions` 写进黑板，Writer 重写时把这些意见拼进 Prompt。这就是“反思”能生效的关键。

## 面试题

1. 请画出一个内容生产 Multi-Agent 系统的完整架构图，并标注消息流。
2. Coordinator 的“静态编排”和“动态编排（Supervisor）”各有什么优缺点？
3. 为什么说 Coordinator 本质是状态机？终止态没设好会怎样？
4. 黑板模式（Blackboard）相比 Agent 直接互调有什么优势？
5. Agent 的生命周期包含哪些阶段？为什么异常要在 `onError` 统一兜底而非抛给上层？

## 本章练习答案（要点）

- 练习：为“AI 招聘”场景设计六大件。
  参考：Coordinator=招聘流程编排；Planner=把“招一个 Java 工程师”拆成 JD 解析→简历筛选→出题→评估；Executor=简历筛选 Agent、出题 Agent；Reviewer=评估打分 Agent（独立，避免既筛又评）；SharedMemory=候选人档案黑板；Communication=标准 Message（候选人 id、阶段、结论）。

---

## 🎯 本章任务（请你完成，我再进入第三章）

> **请你画出本项目的完整架构图**（可用文字/ASCII/画图工具），要求包含：五大角色 + SharedMemory + 完整消息流 + 终止判定。
>
> 画完发我，我们进入 **第三章：用 Java 设计遵循 SOLID 的 Agent 框架（Agent 接口、Task、Message、SharedMemory、AgentManager、Coordinator）**，并开始写第一批可运行代码。
```
```