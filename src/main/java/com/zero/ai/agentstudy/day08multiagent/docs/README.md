# Day08：Multi-Agent（多智能体协作）——让 Agent 拥有团队协作能力

> 《30 天打造商业级 AI Agent 平台（Java 版）》 · Day08
>
> 本文是 Day08 的“总纲”文档。即使你以后完全不看聊天记录，也能仅凭本 README + `docs/chapters/*` 重新学习并复现今天的全部内容。
>
> **重要约定**：Day08 的所有代码只允许放在 `day08multiagent` 目录下，绝不修改 Day01–Day07 任何代码，保证独立运行。

---

## 目录

1. [今日学习目标](#1-今日学习目标)
2. [Multi-Agent 发展历史](#2-multi-agent-发展历史)
3. [为什么单 Agent 越来越不够](#3-为什么单-agent-越来越不够)
4. [Multi-Agent 整体架构](#4-multi-agent-整体架构)
5. [Agent 通信方式](#5-agent-通信方式)
6. [协同策略](#6-协同策略)
7. [主流框架对比（AutoGen / CrewAI / LangGraph / Spring AI）](#7-主流框架对比)
8. [企业应用案例](#8-企业应用案例)
9. [今天项目介绍](#9-今天项目介绍)
10. [今日知识总结](#10-今日知识总结)

---

## 1. 今日学习目标

Day08 只有一个核心目标：**理解并用 Java 实现一个支持多 Agent 协同工作的最小可用平台**。

我们把这个大目标拆成可验证的小目标：

- **认知层**：说清楚“单 Agent”和“多 Agent”的本质区别，以及为什么复杂任务必须靠“团队”而不是“个人”来完成。你要能对老板/面试官讲明白：Multi-Agent 到底解决了什么工程问题。
- **架构层**：掌握 Multi-Agent 的五大核心角色——Coordinator（协调者）、Planner（规划者）、Executor（执行者，本项目细化为 Research/Writer 等具体执行 Agent）、Reviewer（评审者）、Shared Memory（共享记忆），并理解它们之间的消息流与生命周期。
- **工程层**：用 Java 从零设计一套遵循 SOLID 的 Agent 框架：`Agent` 接口、`AgentContext`、`Task`、`Message`、`SharedMemory`、`AgentManager`、`Coordinator`。想新增一个 Agent 只需实现接口，Coordinator 一行不用改（开闭原则）。
- **落地层**：实现四个真实 Agent——PlannerAgent（拆解任务）、ResearchAgent（收集素材）、WriterAgent（撰写正文）、ReviewerAgent（审校打分），串起一个完整的“内容生成”协作流程。
- **整合层**：把 Day06 的 Workflow 思想、Day07 的 MCP 思想与 Day08 的 Multi-Agent 打通，理解 `Workflow → Coordinator → 多个 Agent → MCP → Tool → 结果汇总` 这条企业级调用链。
- **平台层**：完成最终项目 **AI 内容生产平台 V1**：输入“帮我写一篇 AI 工具推荐文章”，系统自动经过 Planner → Research → Writer → Reviewer，产出一篇 Markdown 文章，并记录每个 Agent 的完整执行日志。

学完 Day08，你应该能够回答这几个“灵魂问题”：

- 为什么一个能力很强的单 Agent，做复杂任务反而不如几个各司其职的 Agent？
- Coordinator / Planner / Executor / Reviewer 各自的职责边界在哪里？
- Multi-Agent 和传统微服务、和 Workflow 到底有什么异同？
- 企业里为什么愿意为“多 Agent”付出更高的 Token 成本和更复杂的架构？
- 如何避免 Agent 之间死循环、重复劳动、互相推诿？

---

## 2. Multi-Agent 发展历史

**Multi-Agent System（MAS，多智能体系统）** 并不是 2023 年才出现的新词，它在学术界已经有三十多年的历史。但“基于大模型的 Multi-Agent”确实是最近两年才真正走向工程落地。我们用一条时间线建立心智模型：

- **1980s–1990s（学术起源）**：分布式人工智能（DAI）领域提出 Agent 概念，研究多个自治实体如何通过通信、协商、协作完成单个实体无法完成的任务。经典理论包括 BDI（Belief-Desire-Intention，信念-愿望-意图）模型、黑板系统（Blackboard）、合同网协议（Contract Net Protocol）。**注意：今天我们做的 Coordinator 分派任务、Agent 竞标/领任务，本质就是合同网思想的复现。**
- **2000s（工程化尝试）**：出现 JADE（Java Agent DEvelopment Framework）等 Java 多智能体框架，主要用于电信、物流、仿真调度。此时的 Agent 是“写死规则的程序”，没有推理能力。
- **2022 年底（LLM 引爆）**：ChatGPT 让 Agent 第一次拥有了“通用推理与语言理解能力”。人们发现：只要给 LLM 一个角色（System Prompt），它就能扮演一个“会思考的 Agent”。
- **2023 年（单 Agent 元年）**：AutoGPT、BabyAGI 出现，让单个 LLM Agent 自主规划、调用工具、循环执行。但很快暴露问题——单 Agent 容易“跑偏、死循环、上下文爆炸、什么都想自己干”。
- **2023 下半年 – 2024（Multi-Agent 元年）**：微软开源 **AutoGen**（多 Agent 对话协作）、**CrewAI**（角色化团队）、**MetaGPT**（模拟软件公司：产品经理→架构师→工程师→测试）等框架相继爆火。核心洞察是：**与其造一个“全能的神”，不如组建一支“各有专长的团队”。**
- **2024（编排范式成熟）**：LangChain 推出 **LangGraph**，用“图（Graph）+ 状态（State）”来编排多 Agent，支持 Supervisor（主管）模式；Anthropic 提出 MCP 统一工具连接（这就是我们 Day07 学的内容）。
- **2025（走向企业平台）**：Multi-Agent 从 Demo 走向生产。企业开关心可观测性、权限、成本、失败恢复、Agent 版本管理。Spring AI、LangChain4j 等 JVM 框架也陆续给出 Agent/编排能力。

一句话概括历史意义：**LLM 让“单个 Agent 会思考”，Multi-Agent 让“一群会思考的 Agent 能协作”——这是从“工具”到“组织”的跃迁。**

---

## 3. 为什么单 Agent 越来越不够

这是今天最重要的认知，请务必想透。我们从五个角度论证“单 Agent 的天花板”。

### 3.1 上下文窗口与注意力稀释

单 Agent 处理复杂任务时，System Prompt 里要塞进“你是规划者，也是研究员，也是作家，也是审校，还要会用 10 个工具……”。角色越多，Prompt 越长，模型的注意力被稀释，**每一项都做得平庸**。而人类团队里，产品经理不需要精通编译原理，作家不需要懂 SEO 算法——专注带来专业。

### 3.2 职责耦合导致不可维护

把规划、检索、写作、审校全写进一个 Prompt/一段代码里，任何一处想调整（比如换一种审校标准），都要动这个“巨型 Prompt”，牵一发而动全身。这违反**单一职责原则（SRP）**。多 Agent 把每个职责独立成一个 Agent，改审校只改 ReviewerAgent。

### 3.3 缺乏“对抗与校验”

单 Agent 是“自己写、自己认”，没有第二双眼睛。它幻觉出一个不存在的 API，自己不会发现。多 Agent 引入 **Reviewer 角色**，形成“生成者 vs 评审者”的对抗结构（类似 GAN 的思想，也类似代码 Review），显著提升输出质量。

### 3.4 无法并行与扩展

单 Agent 是串行的“独木桥”。而现实任务往往可以并行：写一篇行业报告，可以同时让 3 个 ResearchAgent 分别调研 A/B/C 三家公司。多 Agent 天然支持**并行、水平扩展**。

### 3.5 无法复用与组合

单 Agent 是“一次性脚本”。多 Agent 里每个 Agent 是可复用的“能力单元”——今天 WriterAgent 用于写文章，明天可以复用到写周报、写营销文案。Agent 变成了可编排、可组合的“乐高积木”。

> **核心结论**：单 Agent 的问题不是“不够聪明”，而是“一个人扛所有职责”这件事本身在工程上不可持续。Multi-Agent 的本质是**用组织结构换取专业性、可维护性、可校验性、可扩展性**。

---

## 4. Multi-Agent 整体架构

本项目采用经典的 **Coordinator-Centric（协调者中心）** 架构，也叫 Supervisor 模式。整体分层如下：

```
                          ┌─────────────────────────┐
   用户请求 ──────────────▶│      Controller (API)   │
   "帮我写一篇AI工具文章"    └────────────┬────────────┘
                                        │ 提交 Task
                                        ▼
                          ┌─────────────────────────┐
                          │      Coordinator        │  ← 大脑/项目经理
                          │  (任务调度 / 流程编排)     │
                          └────┬───────┬───────┬─────┘
             分派①            │       │       │            分派④
          ┌──────────────────┘       │       └──────────────────┐
          ▼                          ▼(分派②/③)                 ▼
  ┌───────────────┐        ┌───────────────────┐       ┌───────────────┐
  │ PlannerAgent  │        │ Research / Writer │       │ ReviewerAgent │
  │  拆解任务       │        │      Agent        │       │   审校打分      │
  └───────┬───────┘        └─────────┬─────────┘       └───────┬───────┘
          │                          │                         │
          └──────────────┬───────────┴─────────────┬───────────┘
                         ▼                          ▼
                  ┌───────────────────────────────────────┐
                  │           Shared Memory (共享记忆)       │
                  │  存放：计划、素材、草稿、评审意见、日志      │
                  └───────────────────────────────────────┘
```

### 核心组件职责

| 组件 | 角色比喻 | 职责 | 对应类（Day08） |
|------|---------|------|----------------|
| **Coordinator** | 项目经理 | 接收任务、决定调用哪个 Agent、按什么顺序、汇总结果 | `Coordinator` |
| **Planner** | 规划师 | 把大任务拆成有序子任务（文章大纲） | `PlannerAgent` |
| **Executor** | 一线员工 | 真正干活（本项目细分为 Research/Writer） | `ResearchAgent` / `WriterAgent` |
| **Reviewer** | 质检员 | 审校产出、打分、给修改意见 | `ReviewerAgent` |
| **Shared Memory** | 项目文档库 | 所有 Agent 共享的上下文黑板 | `SharedMemory` |
| **Message** | 工单/邮件 | Agent 间传递信息的标准载体 | `Message` |
| **AgentManager** | 人事/花名册 | 注册、查找、管理所有 Agent | `AgentManager` |

---

## 5. Agent 通信方式

Agent 之间如何“说话”？主要有三种范式，本项目采用第 2 种为主：

1. **直接消息（Direct Message）**：A 直接把 `Message` 发给 B。像同事之间面对面沟通。简单直接，但耦合度高（A 要知道 B 的存在）。
2. **黑板 / 共享记忆（Blackboard / Shared Memory）**：Agent 不直接对话，而是把结果写到一块公共“黑板”（`SharedMemory`）上，其他 Agent 从黑板读取自己需要的信息。**解耦、可追溯、天然支持异步**。本项目主用此模式。
3. **消息总线 / 事件驱动（Message Bus）**：引入一个中央 Broker，Agent 发布/订阅事件。适合超大规模、跨进程的 Agent 集群（对应 Kafka / RabbitMQ）。是本项目 TODO 中的进阶挑战。

在本项目里，一次协作的“消息流”长这样：

```
Coordinator ──(Task: 写文章)──▶ PlannerAgent
PlannerAgent ──(写入 outline)──▶ SharedMemory
Coordinator ──(读取 outline)──▶ ResearchAgent
ResearchAgent ──(写入 materials)──▶ SharedMemory
Coordinator ──(读取 outline+materials)──▶ WriterAgent
WriterAgent ──(写入 draft)──▶ SharedMemory
Coordinator ──(读取 draft)──▶ ReviewerAgent
ReviewerAgent ──(写入 review + score)──▶ SharedMemory
Coordinator ──(汇总)──▶ 返回最终 Markdown
```

---

## 6. 协同策略

企业中常见的 Multi-Agent 协同策略有以下几种，选型取决于任务形态：

- **顺序流水线（Sequential / Pipeline）**：Planner → Research → Writer → Reviewer，一环扣一环。**本项目 V1 采用此策略**，因为内容生产天然有先后依赖。
- **主管分派（Supervisor / Hierarchical）**：一个 Coordinator 决定每一步调谁，可以循环、可以跳过。本项目的 Coordinator 已具备这个骨架，未来可扩展成“Reviewer 不满意就打回给 Writer 重写”的闭环。
- **并行 + 聚合（Parallel + Aggregate / Map-Reduce）**：多个 Agent 同时干活，最后汇总。适合“调研多个对象”。
- **辩论 / 投票（Debate / Voting）**：多个 Agent 对同一问题给出方案，再投票或辩论出最优解。适合决策类任务。
- **反思闭环（Reflection Loop）**：生成→评审→根据评审重新生成，循环若干轮直到达标。本项目通过 Reviewer 打分 + Coordinator 判断实现最简版反思。

---

## 7. 主流框架对比

理解思想比记框架更重要。这里横向对比，帮你把今天写的 Java 代码对号入座：

| 框架 | 语言 | 核心思想 | 协同方式 | 与本项目对应 |
|------|------|---------|---------|-------------|
| **AutoGen**（微软） | Python | 可对话的 Agent，通过“群聊”协作 | 多 Agent 对话 + GroupChatManager | GroupChatManager ≈ 我们的 Coordinator |
| **CrewAI** | Python | 角色化团队（Crew），每个 Agent 有 role/goal/backstory | 顺序 / 层级流程 | Crew ≈ AgentManager，Process ≈ 协同策略 |
| **LangGraph**（LangChain） | Python | 图 + 状态机编排，节点即 Agent | Supervisor / 条件边 | State ≈ 我们的 SharedMemory，Node ≈ Agent |
| **MetaGPT** | Python | 模拟软件公司 SOP | 固定角色流水线 | 和我们“内容生产流水线”思想一致 |
| **Spring AI** | Java | Advisor / ChatClient / Function 抽象 | 目前偏单 Agent + 工具，编排能力演进中 | 我们手写 Coordinator 补足编排 |
| **LangChain4j** | Java | AiServices / Tool 抽象 | 单 Agent + Tool 为主 | 同上 |

> **给 Java 工程师的重要提醒**：目前 Multi-Agent 生态的“最佳实践”大多长在 Python 生态。Java 侧（Spring AI / LangChain4j）的多 Agent 编排能力还在快速演进。**所以我们今天“手写”一套 Coordinator 框架，恰恰是让你理解这些框架内部到底在干什么**——这正是架构师和调包侠的区别。

---

## 8. 企业应用案例

Multi-Agent 已经在大量真实场景落地。下面每个案例都说明“为什么必须多个 Agent”：

- **AI 客服**：意图识别 Agent（分类）+ 知识检索 Agent（RAG）+ 话术生成 Agent + 情绪安抚 Agent + 工单转人工 Agent。单个 Agent 无法同时兼顾“准确分类”和“共情表达”。
- **AI 办公 / 助理**：日程 Agent + 邮件 Agent + 文档 Agent + 会议纪要 Agent，各自对接不同系统（对应不同 MCP Server）。
- **AI 招聘**：JD 解析 Agent + 简历筛选 Agent + 面试出题 Agent + 评估打分 Agent。筛选和评估必须解耦，否则“既当运动员又当裁判”。
- **AI 法务**：合同条款抽取 Agent + 风险识别 Agent + 合规校验 Agent + 意见书撰写 Agent。风险识别需要专门的“对抗性审查”角色，即 Reviewer。
- **AI 医生（辅助诊断）**：问诊 Agent + 检验解读 Agent + 诊断建议 Agent + 用药安全审查 Agent。用药审查是独立的安全兜底 Agent，绝不能和诊断 Agent 合并。
- **AI 研发助手**：需求分析 Agent + 架构设计 Agent + 编码 Agent + 测试 Agent + Code Review Agent（这正是 MetaGPT 的思路）。
- **AI 数据分析**：取数 Agent（写 SQL）+ 清洗 Agent + 分析 Agent + 可视化 Agent + 结论撰写 Agent。
- **AI 量化交易**：行情采集 Agent + 因子计算 Agent + 策略决策 Agent + 风控 Agent。风控 Agent 拥有一票否决权，必须独立。
- **AI 内容生产**（**本项目**）：Planner（选题+大纲）+ Research（素材）+ Writer（成文）+ Reviewer（审校）。这是最能体现“流水线协作”的经典场景，所以选它作为 Day08 的落地项目。

**共同规律**：凡是任务链路长、需要多种专业能力、需要“质检/风控兜底”、需要并行处理的场景，都天然适合 Multi-Agent。

---

## 9. 今天项目介绍

**项目名称：AI 内容生产平台 V1**

**输入**：一句话需求，例如 `"帮我写一篇 AI 工具推荐文章"`。

**处理流程**：

```
用户请求
   │
   ▼
Coordinator（协调者，编排整个流程）
   │
   ├─▶ PlannerAgent   ：产出文章大纲（若干小节标题）
   │
   ├─▶ ResearchAgent  ：为每个小节收集要点/素材
   │
   ├─▶ WriterAgent    ：根据大纲+素材撰写正文
   │
   └─▶ ReviewerAgent  ：审校、打分、给出修改意见
   │
   ▼
最终输出：一篇 Markdown 文章 + 完整执行日志
```

**技术要点**：

- 遵循 SOLID：`Agent` 接口统一抽象，新增 Agent 不改 Coordinator（OCP）。
- 共享记忆：所有 Agent 通过 `SharedMemory` 交换数据，解耦、可追溯。
- 可观测：每个 Agent 执行都记录到 `AgentExecutionLog`，形成完整调用链。
- 可降级：为了让项目**开箱即运行、不强依赖真实大模型 Key**，Agent 内部采用“可插拔 LLM 客户端”，默认提供一个规则/模板实现（Echo/Mock），配置了真实 Key 后可无缝切换。这延续了 Day05/Day07 的做法。

**目录结构**：

```
day08multiagent
├── controller/                 REST 接口层
├── service/                    应用服务层（编排入口）
├── agent/
│   ├── core/                   Agent 接口、上下文、抽象基类、执行结果
│   ├── planner/                PlannerAgent
│   ├── research/               ResearchAgent
│   ├── writer/                 WriterAgent
│   ├── reviewer/               ReviewerAgent
│   ├── coordinator/            Coordinator（编排大脑）
│   ├── message/                Message、Task 消息模型
│   └── memory/                 SharedMemory 共享记忆
├── config/                     配置、Agent 注册
├── entity/                     领域实体
├── dto/                        请求/响应对象
├── util/                       工具类（日志追踪等）
└── docs/                       本文档 + 章节 + 架构 + TODO
```

---

## 10. 今日知识结

- **一句话**：Multi-Agent 的本质是“把一个全能巨人，拆成一支各有专长、能协作的团队”，用组织结构换取专业性、可维护性、可校验性和可扩展性。
- **五大角色**：Coordinator（编排）、Planner（拆解）、Executor（干活）、Reviewer（质检）、Shared Memory（黑板）。
- **三种通信**：直接消息、共享记忆（本项目主用）、消息总线。
- **五种协同**：顺序流水线（本项目 V1）、主管分派、并行聚合、辩论投票、反思闭环。
- **工程红线**：避免死循环（限制轮次）、避免重复劳动（结果入共享记忆）、控制 Token 成本、全链路日志、权限隔离。
- **与微服务的关系**：形似（都是拆分、都靠通信协作），神不同（微服务是确定性 RPC，Multi-Agent 是概率性推理协作）——这正是第一章要你回答的问题。

> **下一步**：请阅读 `docs/chapters/chapter-01.md`（为什么需要 Multi-Agent），完成后回答思考题，我们再进入第二章。