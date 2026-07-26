# Day6：Workflow（工作流）——Agent 开始拥有思考和执行能力

> 《30天打造商业级 AI Agent 平台（Java 版）》第 6 天学习资料
>
> 前置知识：Day1 LLM 原理、Day2 API 调用、Day3 Function Calling、Day4 Memory、Day5 RAG。
> 本文档独立完整，即使不看聊天记录，也能凭此重学 Day6 全部内容。

---

## 一、今日学习目标

Day1~Day5，我们让 Agent 拥有了「四种基础能力」：

| Day | 能力 | 一句话 |
|-----|------|--------|
| Day1 | LLM 原理 | Agent 的「语言大脑」 |
| Day2 | API 调用 | 让程序能和大脑对话 |
| Day3 | Function Calling | 让大脑能「动手」调用外部工具 |
| Day4 | Memory | 让大脑「记得住」上下文 |
| Day5 | RAG | 让大脑「查得到」外部知识 |

但你会发现一个致命问题：**这些能力都是「单步」的。**
用户问一句 → LLM 想一下 → 调一个工具 → 回一句。

真实的企业任务从来不是「单步」的。比如「帮我规划一次北京三游」，它天然包含多个步骤：
识别城市 → 查天气 → 查酒店 → 根据天气调整方案 → 生成行程 → 输出 Markdown。
这些步骤**有顺序、有依赖、有分支、可能失败要重试、甚至需要人来确认**。

这正是 **Workflow（工作流）** 要解决的问题。

**学习结束后，你必须能真正回答：**

1. 为什么 Agent 需要 Workflow？
2. Workflow 和普通 Java 流程控制（if/else、for、try/catch）有什么本质区别？
3. Workflow 如何与 Function Calling、Memory、RAG 协同？
4. 为什么几乎所有企业级 Agent 平台都会内置 Workflow 引擎？
5. 如何从零设计一个可扩展的 Workflow 框架？

**最终产出：** 一个可独立运行的企业级 Workflow Agent Demo —— Travel Agent（旅行规划智能体）。

---

## 二、Workflow 知识体系

Workflow 不是一个孤立的概念，它是一整套「把复杂任务拆解成可编排、可执行、可观测、可恢复的步骤图」的方法论。它的知识体系可以分为四层：

### 第 1 层：概念层（是什么）

- **Workflow（工作流）**：一组有向连接的执行步骤，描述「一件事怎么一步步做完」。
- **Node（节点）**：工作流的最小执行单元，一个 Node 只做一件事（单一职责）。
- **Edge（边 / 转移）**：连接两个 Node，决定「做完这一步接下来去哪一步」。
- **State / Context（状态 / 上下文）**：贯穿整个流程的「共享数据袋」，Node 从中读、往里写。
- **Action（动作）**：Node 真正执行的业务逻辑（调 LLM、调工具、查 RAG、写库）。
- **Condition（条件）**：在 Edge 上判断走哪条分支（如「下雨 → 室内方案」）。
- **Loop（循环）**：反复执行某些 Node（如 ReAct 的「思考-行动」循环）。
- **Retry（重试）**：Node 失败后自动再试，是企业级容错的核心。
- **Human-in-the-loop（人在环中）**：流程暂停，等待人工审批 / 补充后再继续。

### 第 2 层：引擎层（怎么跑）

- **Execution Engine（执行引擎）**：读取 Workflow 定义，按 Edge 依次调度 Node，管理 Context、日志、重试、超时。
- **Scheduler（调度器）**：决定下一个执行哪个 Node（串行 / 并行 / 条件跳转）。
- **NodeResult（节点结果）**：每个 Node 返回统一结构（成功 / 失败 / 下一步指向）。

### 第 3 层：设计模式层（为什么这样写）

Workflow 的经实现大量借用 GoF 设计模式：

- **责任链模式（Chain of Responsibility）**：Node 像链条一个接一个处理。
- **状态模式（State）**：不同状态下有不同行为，状态之间自动流转。
- **策略模式（Strategy）**：同一步骤可插拔不同实现（如不同 LLM、不同酒店源）。
- **命令模式（Command）**：把「一个操作」封装成对象，可排队、可撤销、可记录日志。
- **Pipeline / 管道模式**：数据像水流过一节节管道被逐步加工。

### 第 4 层：企业能力层（怎么上线）

- 配置化（流程写在 DB / JSON，不写死在代码）
- 可视化编排（拖拽画流程图）
- 执行日志与追踪（每个 Node 的输入输出可回溯）
- 断点恢复（宕机从上次成功的 Node 继续）
- 异步与并行（提升吞吐）
- 版本管理（流程升级不影响运行中的实例）

---

## 三、今日学习路线（8 章）

```
第一章  为什么需要 Workflow？          （思想：Agent 的大脑）
   ↓
第二章  Workflow 底层原理              （Node/Edge/State/Engine）
   ↓
第三章  Workflow 设计模式              （责任链/状态/策略/命令/Pipeline）
   ↓
第四章  Java 实现第一个 Workflow       （旅行规划：4 个独立 Node）
   ↓
第五章  实现 Workflow Engine V1        （可扩展的小型框架）
   ↓
第六章  企业能力                       （条件/重试/异常/日志/超时）
   ↓
第七章  企业 Workflow 设计             （配置化/解耦/可视化/LangGraph 思想）
   ↓
第八章  最终项目 Travel Agent          （天气驱动 + Markdown 行程 + 执行日志）
```

**学习原则：讲完一章暂停，等你确认后再继续下一章。** 采用「大学教授 + 企业导师 + Pair Programming + 架构评审」四合一方式。

---

## 四、核心概念速查

### 4.1 Workflow vs 普通 Java 流程

| 维度 | 普通 Java 流程（if/for/try） | Workflow |
|------|------------------------------|----------|
| 编排方式 | 写死在代码里 | 定义成「图」，可配置 |
| 步骤 | 方法调用，耦合在一起 | 独立 Node，可插拔 |
| 状态 | 局部变量、栈 | 显式 Context，可持久化 |
| 分支 | if/else | Edge + Condition |
| 失败 | try/catch 局部处理 | 引擎统一重试 / 恢复 |
| 可观测 | 靠打日志 | 每步天然可追踪 |
| 恢复 | 几乎不可能 | 可断点续跑 |
| 修改流程 | 改代码、重新发布 | 改配置、热更新 |

**一句话：普通流程是「代码控制流」，Workflow 是「可编排、可持久化、可观测的执行图」。**

### 4.2 Workflow 与 Day3~Day5 的协同

```
        ┌─────────────── Workflow Engine（大脑/指挥官）───────────────┐
        │                                                            │
   [Node: 理解意图]→[Node: RAG 检索]→[Node: Tool 调用]→[Node: 生成回复] │
        │  用 LLM       用 Day5 RAG      用 Day3 Tool     用 LLM        │
        │                                                            │
        └──────── 全程读写 Context（内含 Day4 Memory）───────────────┘
```

- **Function Calling（Day3）** 是 Workflow 里「某个 Node 的动作」。
- **Memory（Day4）** 是 Workflow 的 Context 中「跨步骤/跨会话」的记忆部分。
- **RAG（Day5）** 是 Workflow 里「检索类 Node」的实现。
- **Workflow（Day6）** 是把它们「编排起来」的指挥官。

---

## 五、企业应用场景

| 场景 | Workflow 在哪一步发挥作用 |
|------|--------------------------|
| AI 办公助手 | 理解指令 → 查日程 → 发邮件 → 生成纪要（多步编排） |
| AI 客服 | 意图识别 → 查订单 → 查知识库 → 判断是否转人工（条件分支 + HITL） |
| AI 审批流 | 提单 → 规则校验 → 多级审批 → 通知（状态机 + 人在环中） |
| AI 招聘 | 简历解析 → 打分 → 匹配岗位 → 生成面试题（Pipeline） |
| AI 知识库 | 文档入库 → 切分 → 向量化 → 建索引（数据管道） |
| AI 数据分析 | 取数 → 清洗 → 计算 → 生成图表 → 写报告（并行 + 依赖） |
| AI 量化交易 | 拉行情 → 策略计算 → 风控校验 → 下单 → 记录（重试 + 风控节点） |
| AI 公众号助手 | 选题 → 检索资料 → 生成草稿 → 审核 → 排版发布（HITL + 版本管理） |

**共同规律：任务越复杂、越关键，越离不开 Workflow。**

---

## 六、最终项目介绍：Travel Agent（旅行规划智能体）

一个体现 Workflow 全部核心能力的可运行 Demo：

**业务流程：**

```
[输入城市] → [查询天气(Tool)] → [查询酒店(模拟)] → [根据天气生成计划(LLM/规则)] → [输出 Markdown 行程]
```

**技术亮点：**

1. 每一步都是独立 `WorkflowNode`，符合单一职责，可插拔、可复用。
2. 自研 `WorkflowEngine`：负责调度、Context 传递、日志、重试、超时、条件分支。
3. 支持「天气驱动的条件分支」：晴天推荐户外，雨天推荐室内。
4. 全程记录 `WorkflowExecutionLog`，执行后可回溯每一步的输入输出耗时。
5. 复用 Day3 的 `WeatherTool` 思想（模拟天气），体现「Tool 是 Node 的动作」。
6. 提供 REST 接口，输入城市即可返回完整 Markdown 旅行方案 + 执行日志。

**目录结构（企业规范）：**

```
day06workflow
├── controller     # 对外 REST 接口
├── service        # 业务编排入口
├── workflow
│   ├── core       # Node/Edge/State 等核心抽象
│   ├── engine     # 执行引擎
│   ├── node       # 具体节点实现
│   ├── context    # 上下文
│   ├── executor   # 执行器
│   └── model      # 结果模型
├── tool           # 工具（天气/酒店）
├── config         # 配置
├── entity         # 实体
├── dto            # 数据传输对象
├── util           # 工具类
└── docs           # 学习文档（README/ARCHITECTURE/TODO/chapters）
```

---

## 七、学习成果检查清单

学完 Day6，你应该能：

- [ ] 说清 Workflow 与普通 Java 流程的 5 个本质区别
- [ ] 画出 Node/Edge/State/Engine 的完整生命周期流程图
- [ ] 说出 Workflow 用到的 5 种设计模式及原因
- [ ] 独立实现一个可扩展的 Workflow Engine
- [ ] 为 Node 加上重试、超时、条件分支、执行日志
- [ ] 解释企业为什么用配置化 + 可视化编排，而不是巨大 if-else
- [ ] 跑通 Travel Agent 并读懂它的执行日志

> 下一步：进入 **第一章 —— 为什么需要 Workflow？**