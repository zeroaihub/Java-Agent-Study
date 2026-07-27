# Day10：Planning Agent（规划智能体）——Agent 从「被驱动」走向「自主」的分水岭

> 《30天打造商业级 AI Agent 平台（Java 版）》第 10 天学习资料
>
> 前置知识：Day1 LLM 原理、Day2 API、Day3 Function/Tool Calling、Day4 Memory、Day5 RAG、Day6 Workflow、Day7 MCP、Day8 Multi-Agent、Day9 Browser Agent。
>
> **本文档完全独立**：即使你从未看过前九天的聊天记录，也能凭这份 README + chapters 从零重学整个 Planning Agent。不假设你懂任何 Planning 知识。

---

## 一、课程介绍

Day1~Day9，我们让 ZeroHub AI Agent Platform 拥有了「九种能力」：

| Day | 能力 | 一句话 | 本质 |
|-----|------|--------|------|
| Day1 | LLM 原理 | Agent 的语言大脑 | 会思考 |
| Day2 | API / Streaming | 程序能和大脑对话 | 会说话 |
| Day3 | Function / Tool Calling | 大脑能动手调工具 | 会用手 |
| Day4 | Memory | 大脑记得住上下文 | 有记忆 |
| Day5 | RAG | 大脑查得到外部知识 | 会查资料 |
| Day6 | Workflow | 复杂任务按图编排执行 | 会走流程 |
| Day7 | MCP | 标准协议接入外部能力 | 会接插件 |
| Day8 | Multi-Agent | 多个 Agent 协作分工 | 会组队 |
| Day9 | Browser Agent | 会操作真实浏览器 | 会上网 |

但请注意，**这九种能力有一个共同的隐含前提：流程是「人」或「预先写死的代码」决定的。**

- Workflow 的图（DAG）是**工程师提前画好的**；
- Multi-Agent 谁先谁后是**架构师编排好的**；
- Browser Agent 点哪个按钮，往往是**Prompt 里指定好的**。

也就是说，到 Day9 为止，Agent 依然是**「被驱动」**的：它很聪明，但它在**执行别人定好的剧本**。

**Planning Agent 要解决的，是让 Agent 自己写剧本。**

给它一个模糊的高层目标——比如「帮我分析 GitHub Trending 今天最热门的 AI Agent 项目，并总结生成 Markdown」——它要能：

1. 理解目标 → 2. 自己拆解成子任务 → 3. 自己排优先级和依赖 → 4. 自己选工具 → 5. 逐步执行 → 6. 检查结果对不对（反思）→ 7. 错了就自我修正、重新规划 → 8. 直到达成目标。

**这就是「自主性（Autonomy）」的分水岭。** Claude Code、Cursor Agent、Devin、Manus、AutoGPT 之所以被称为「Agent」而不是「ChatBot」，核心区别就在于它们都有一个 **Planning Engine（规划引擎）**。

**Day10 学完，你将亲手写出一个企业级 Planning Agent 模块**，包含 8 个子模块：`planner-core / planner-engine / planner-memory / planner-tool-selector / planner-reflection / planner-executor / planner-context / planner-api`，并把它接入 ZeroHub 平台。

---

## 二、Planning Agent 的发展历史

理解「为什么」比理解「怎么做」更重要。Planning 不是 LLM 时代的新发明，它有一条清晰的技术演进线：

### 1. 经典 AI 规划时代（1970s~2000s）：符号规划

- **STRIPS（1971）**：斯坦福研究院机器人「Shakey」的规划系统，用「前置条件 + 效果」描述动作，通过状态搜索找到从初始态到目标态的动作序列。这是「Goal → Actions」思想的鼻祖。
- **PDDL（1998）**：规划领域定义语言，把「领域、动作、目标」标准化，至今仍是学术界规划竞赛的标准。
- 特点：**逻辑严密、可证明最优，但需要人把世界建模成符号**，无法处理开放的自然语言目标。

### 2. 强化学习规划（2010s）：搜索 + 学习

- **AlphaGo（2016）**：MCTS（蒙特卡洛树搜索）+ 神经网络，本质是「在巨大状态空间里做前瞻规划」。
- 特点：**能在复杂空间里规划，但需要明确的奖励信号和海量训练**，不通用。

### 3. LLM 规划时代（2022~今）：语言即规划

真正的转折点是 **LLM 具备了「用语言做推理」的能力**，规划第一次可以直接作用于自然语言目标：

- **Chain-of-Thought / CoT（2022）**：让 LLM「一步步想」，是「多步推理」的起点。
- **ReAct（2022，Google）**：**Reasoning + Acting** 交替进行——「想一步（Thought）→ 做一步（Action）→ 看结果（Observation）→ 再想」。这是**当今几乎所有 Agent 的执行内核**。
- **Plan-and-Solve（2023）**：先整体规划出完整步骤，再逐步执行，减少「走一步看一步」的短视。
- **Reflexion（2023）**：给 Agent 加「反思」——执行失败后总结教训，写入记忆，下次规划时避开。这是 **Reflection / Self-Correction** 的理论源头。
- **Tree of Thoughts / ToT（2023）**：把推理组织成「思维树」，可以回溯、可以搜索多条路径。
- **AutoGPT / BabyAGI（2023）**：第一批「全自主 Agent」，展示了「目标 → 自动拆任务 → 自动执行 → 循环」的完整闭环，也暴露了「死循环、烧 Token、跑偏」等经典问题。
- **工程化落地（2024~）**：Devin（AI 软件工程师）、Manus（通用 Agent）、Claude Code、Cursor Agent——把 Planning 从「玩具 Demo」做成了「企业级产品」，核心是加了大量**工程约束**：预算控制、可观测、人在环中、失败恢复、状态持久化。

**一句话总结历史：规划从「符号逻辑」→「搜索学习」→「语言推理」，而企业级 Planning Agent = LLM 的语言推理能力 + 经典规划的工程严谨性。** 我们 Day10 做的，正是后者。

---

## 三、为什么 Agent 一定需要 Planning？

### 1. 因为真实任务是「多步、有依赖、会失败、目标模糊」的

「帮我分析 GitHub Trending 最热的 AI 项目并生成 Markdown」这句话里，用户**没告诉你**：
- 要看哪个语言分类、看几个项目？
- 先打开浏览器还是先查缓存？
- 提取到的内容要不要二次总结？
- 如果页面打不开怎么办？

这些**决策**必须由 Agent 自己在运行时做出——这就是 Planning。

### 2. 因为 Workflow 是「静态图」，覆盖不了「动态未知」

Day6 的 Workflow 很强，但它的 DAG 是**编译期就画死的**。而真实世界里：
- 你不知道要循环几次才能找到答案；
- 你不知道下一步该调哪个工具（取决于上一步的结果）；
- 你不知道会不会失败、失败后该走哪条备选路径。

**Workflow 回答「怎么走这张既定的图」，Planning 回答「这张图本身应该长什么样」。** 后者由 LLM 在运行时动态生成。

### 3. 因为这是「Agent」和「ChatBot」的根本区别

| 维度 | ChatBot | Planning Agent |
|------|---------|----------------|
| 输入 | 一个问题 | 一个目标 |
| 步骤 | 单步一问一答 | 多步自主执行 |
| 流程 | 无 / 固定 | 运行时动态生成 |
| 失败 | 直接报错 | 反思 + 重规划 |
| 工具 | 人指定或单个 | 自主选择 + 组合 |
| 价值 | 回答问题 | 完成任务 |

---

## 四、LLM 为什么不会「自动」规划？

新手常有一个误区：「GPT 那么聪明，我直接让它规划不就行了？」。这在 Demo 里能跑，在企业里会出事。原因：

1. **LLM 是「无状态的下一个 token 预测器」**，它本身没有「记住我执行到第几步」的能力——状态必须由**外部引擎**维护（这就是 `planner-context` 存在的理由）。
2. **LLM 会「一本正经地编造计划」**，它给的步骤可能引用不存在的工具、跳过关键依赖——必须有 `planner-tool-selector` 和校验层兜底。
3. **LLM 没有「预算意识」**，你不拦它，它能无限循环、把 Token 烧光——必须有引擎层的 `maxSteps / maxTokens / 超时` 硬约束。
4. **LLM 不知道「自己错了」**，它对幻觉毫无自觉——必须有独立的 `planner-reflection` 来审视结果。
5. **LLM 的输出不可靠地结构化**，直接让它输出 JSON 计划，格式经常崩——必须有解析 + 重试 + 降级。

**结论：LLM 提供的是「规划的智能」，而企业级 Planning Engine 提供的是「规划的可靠性」。** 把智能变成可靠的产品，正是工程师的价值所在。

---

## 五、企业为什么要自建 Planning Engine？

- **可控**：预算、步数、超时、工具白名单，全部可治理，不让 Agent 失控烧钱。
- **可观测**：每一步的 Thought / Action / Observation 全部落库，线上问题可追溯到具体步骤（Tracing）。
- **可恢复**：状态持久化到 Redis/PG，宕机后能从上次成功步骤续跑。
- **可扩展**：新工具、新反思策略、新规划算法可插拔，不改核心。
- **可复用**：一个 Planning Engine 支撑 AI Coding、AI Office、AI 客服等多条业务线。
- **可商业化**：这是 AI SaaS 的核心壁垒——谁的 Agent「更能自主、更少翻车」，谁就赢。

---

## 六、完整知识体系（Day10 全景）

Planning Agent 的知识体系分五层，与我们的模块一一对应：

```
第1层 概念层：Goal / Plan / SubTask / TaskTree / TaskGraph / TaskQueue / Priority / Dependency
第2层 推理层：ReAct / CoT / Reflection / Self-Correction / Re-Planning / Multi-Step Reasoning
第3层 引擎层：Planner（规划器）/ Scheduler（调度器）/ Executor（执行器）/ StateMachine（状态机）
第4层 支撑层：Context（上下文/状态）/ Memory（记忆）/ ToolSelector（工具选择）/ Tracing（可观测）
第5层 治理层：Budget（预算）/ Retry（重试）/ Timeout（超时）/ Guardrail（护栏）/ Human-in-loop
```

对应的 8 个代码模块：

| 模块 | 职责 | 关键类（本项目） |
|------|------|------------------|
| `planner-core` | 领域模型 + 目标拆解 | `Goal` `Plan` `PlanStep` `Planner` `LlmPlanner` |
| `planner-engine` | 任务调度 + 依赖 + 状态机 | `PlanScheduler` `TaskGraph` `PlanState` |
| `planner-executor` | 逐步执行 + 失败恢复 | `PlanExecutor` `StepResult` `RetryPolicy` |
| `planner-reflection` | 反思 + 自我修正 | `Reflector` `ReflectionResult` |
| `planner-tool-selector` | 工具选择 | `ToolSelector` `ToolSpec` `ToolRegistry` |
| `planner-context` | 状态/上下文管理 | `PlanningContext` `Blackboard` |
| `planner-memory` | 规划记忆（成功/失败经验） | `PlanningMemory` |
| `planner-api` | 对外 REST 入口 | `PlanningController` `PlanningService` |

（各模块的代码将在 chapter-03 ~ chapter-09 中逐一实现并讲透。）

---

## 七、最终产出与运行效果

**一句话目标输入：**

```
帮我分析 GitHub Trending 今天最热门的 AI Agent 项目，并总结生成 Markdown。
```

**Planning Agent 全自动完成：**

```
① 分析目标 → ② 拆解任务 → ③ 选择 Browser Tool → ④ 浏览 GitHub
→ ⑤ 提取内容 → ⑥ 调用 Summary Tool → ⑦ Reflection 检查结果
→ ⑧ 若失败则重新规划 → ⑨ 输出最终 Markdown
```

**运行步骤（先睹为快，代码在后续章节实现）：**

```bash
# 1. 启动依赖（可选，本 Demo 提供内存实现，不强依赖）
#    Redis / PostgreSQL 用于状态持久化与规划记忆

# 2. 配置 OpenAI Compatible API（application.yml）
#    zero.planning.api-key / base-url / model

# 3. 启动
mvn spring-boot:run

# 4. 调用 Planning Agent
curl -X POST http://localhost:8080/api/day10/planning/run \
  -H "Content-Type: application/json" \
  -d '{"goal":"分析 GitHub Trending 今天最热门的 AI Agent 项目，总结成 Markdown"}'

# 5. 观察返回：完整的 Plan（含每步 Thought/Action/Observation）+ 最终 Markdown
```

---

## 八、如何接入平台其他模块（承上启下）

Planning Agent 是「大脑指挥官」，它调度前九天的所有能力：

- **接 Browser Agent（Day9）**：把「打开浏览器、提取网页」封装为 `Tool`，注册进 `ToolRegistry`，Planner 会在需要上网时自动选它。
- **接 Workflow（Day6）**：当某个子任务本身是「固定多步流程」时，把整条 Workflow 封装成**一个** Tool 交给 Planner 调用（Planning 管「宏观决策」，Workflow 管「微观固定流程」）。
- **接 MCP（Day7）**：MCP 工具通过 `McpToolAdapter` 统一适配成 `ToolSpec`，Planner 无感知地把它们纳入候选池。
- **接 Multi-Agent（Day8）**：Planner 拆出的子任务可以派发给不同的专家 Agent，Planner 本身就是「协调者 Agent」。
- **接 Memory（Day4）/ RAG（Day5）**：`planner-memory` 记录成功/失败经验，规划时先检索历史相似任务，避免重复踩坑。

**为 Day11（Human-in-the-loop）铺垫**：状态机中预留了 `WAITING_HUMAN` 状态和审批断点——高风险步骤（如下单、删库）执行前暂停，等人确认后再继续。

---

## 九、课后总结与企业最佳实践

1. **规划与执行分离**：Planner 只产出计划，Executor 只执行，职责单一、可独立测试。
2. **一切都要有预算**：maxSteps、maxTokens、超时，是防止 Agent 失控的第一道防线。
3. **反思要「有限且有效」**：反思不是越多越好，过度反思会烧 Token 且陷入犹豫，要设次数上限。
4. **状态必须外置**：LLM 无状态，所有进度存 Context，才能持久化、可恢复、可观测。
5. **工具要有白名单和 Schema**：不让 LLM 自由发挥调用什么，必须从注册表里选，且参数要校验。
6. **可观测优先**：先把每步 I/O 记全了，再谈优化——线上排障靠的是 Trace 不是猜。

---

## 十、延伸阅读

- 论文：ReAct、Reflexion、Plan-and-Solve、Tree of Thoughts、Toolformer。
- 项目：AutoGPT、BabyAGI、LangGraph、Manus 技术博客、Devin 技术分享。
- 书籍：《Artificial Intelligence: A Modern Approach》第 10~11 章（经典规划）。

---

> **学习节奏**：本课程「讲一章、停一章」。请打开 `docs/chapters/chapter-01.md` 开始第一章；读完对我说「继续」，再进入下一章。