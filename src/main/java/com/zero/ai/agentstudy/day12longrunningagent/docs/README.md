# Day12 · Long Running Agent（企业级长生命周期 Agent 运行时）

> ZeroHub AI Agent Platform · Long Running Agent 模块
>
> 技术栈：Java 21 (LTS) · Spring Boot 4.1.0 · Spring AI 2.0.0 · Redis 8 · PostgreSQL 17 · pgvector · Playwright Java · Docker
>
> 本模块完全独立，不修改 Day01–Day11 任何代码。README 即可独立学习。

---

## 目录

- [一、Long Running Agent 是什么](#一long-running-agent-是什么)
- [二、为什么 AI Agent 必须支持长生命周期](#二为什么-ai-agent-必须支持长生命周期)
- [三、为什么 ChatBot 不需要，而企业必须需要](#三为什么-chatbot-不需要而企业必须需要)
- [四、完整知识体系](#四完整知识体系)
- [五、核心架构与代码讲解](#五核心架构与代码讲解)
- [六、运行步骤](#六运行步骤)
- [七、效果展示](#七效果展示)
- [八、如何接入 Planning / Browser / Workflow / Memory / HITL](#八如何接入-planning--browser--workflow--memory--hitl)
- [九、未来如何扩展](#九未来如何扩展)
- [十、企业最佳实践](#十企业最佳实践)
- [十一、课后总结](#十一课后总结)
- [十二、章节导航](#十二章节导航)

---

## 一、Long Running Agent 是什么

**Long Running Agent（长生命周期智能体）**，指的是一类"任务不会在一次请求内结束、需要持续运行数小时、数天甚至数周"的 AI Agent。它不是一个"接口调用后立即返回结果"的函数，而是一个**有状态、可恢复、可调度、可观测的常驻进程或工作流实例**。

在传统的 ChatBot 中，一次用户请求对应一次 LLM 调用，请求结束、上下文即可丢弃。而 Long Running Agent 面对的是这样的任务：

> "每天上午 9 点自动登录 GitHub，检查 Trending AI Agent 项目，生成总结，发送企业微信；如果登录失败自动重试；如果生成的总结需要人工确认则暂停等待审批；服务器重启后能够从上次进度继续执行。"

这类任务具有以下本质特征：

1. **时间跨度长**：从分钟级到周级，远超单次 HTTP 请求的生命周期。
2. **有状态**：Agent 必须记住"我执行到哪一步了"、"上一步的产出是什么"。
3. **可中断可恢复**：进程崩溃、机器重启、部署升级都不能让任务从头再来。
4. **异步与事件驱动**：任务可能因为"等待人工审批"、"等待定时触发"、"等待外部回调"而挂起。
5. **需要治理**：重试、超时、死信、监控、日志、限流，缺一不可。

因此，Long Running Agent 的核心不是"更聪明的 Prompt"，而是一套**Agent Runtime（智能体运行时）**——它借鉴了操作系统进程管理、工作流引擎（Temporal / Netflix Conductor）、消息中间件（Kafka / RabbitMQ）的成熟工程范式，把"让 Agent 长期稳定运行"这件事系统化、工程化。

一句话概括：

> **Long Running Agent = LLM 智能 + 操作系统级的生命周期管理 + 工作流引擎级的持久化与恢复能力。**

---

## 二、为什么 AI Agent 必须支持长生命周期

我们先从"一次性 Agent"的局限说起。假设你用 Day10 的 Planning Agent 实现了一个"帮我调研市场并写报告"的任务。它在一次请求里：规划 → 调用工具 → 反思 → 输出。看起来很完美，但一旦放到真实企业场景，问题立刻暴露：

**问题 1：任务根本无法在一次请求内完成。**
真实的调研任务可能需要爬取 200 个网页、调用 50 次 LLM、等待 3 个外部 API 的异步回调。一次 HTTP 请求在网关层通常 30–60 秒就超时了。你不可能让用户对着浏览器转圈 3 个小时。

**问题 2：中间失败等于全部重来。**
爬到第 180 个网页时机器 OOM 了。如果没有 Checkpoint，前面 179 个网页的成果全部丢失，几十次 LLM 调用的 Token 成本打水漂。企业无法接受这种"不可靠"。

**问题 3：任务需要"等待"。**
"生成的报告需要主管审批后才能发送"——这是 Human-in-the-loop。Agent 必须能**挂起（Suspend）**，把状态持久化，然后在几小时后主管点了"同意"时**恢复（Resume）**继续执行。一次性请求做不到"等 3 小时"。

**问题 4：任务需要"定时"与"重复"。**
"每天 9 点自动执行"——这需要 Scheduler。任务执行完不是结束，而是进入"等待下一次触发"的休眠态。这本质上就是一个永不结束的生命周期。

**问题 5：任务需要被"观测"和"治理"。**
企业要知道：现在有多少 Agent 在跑？哪个卡住了？哪个失败了？失败了几次？重试了没有？这就需要 Monitor、Log、Metrics、状态机。

**结论**：只要 Agent 要进入真实生产环境、承担真实业务价值，它就必然要面对"长时间、有状态、可恢复、可调度、可观测"这五座大山。而翻越这五座大山的工程能力，正是 Long Running Agent Runtime 的价值所在。

这也是 2024–2026 年 Agent 领域最重要的工程趋势：**从"Prompt 工程"转向"Agent Runtime 工程"**。OpenAI Operator、Claude Code、Google Jules、Microsoft Copilot Agent、Azure AI Agent Service，本质上都是在解决"如何让 Agent 长期稳定运行"这个问题。

---

## 三、为什么 ChatBot 不需要，而企业必须需要

### 3.1 ChatBot 的世界：无状态、短生命周期

一个典型的 ChatBot（如 Day01–Day02 我们实现的）请求生命周期是这样的：

```
用户提问 --> 组装 Prompt --> 调用 LLM --> 返回答案 --> 结束（上下文可丢弃）
```

即使有多轮对话（Day02 的 ConversationStore），本质上也只是"把历史消息拼进 Prompt"，每一轮依然是一次独立、短暂、无副作用的请求。ChatBot 不需要 Long Running，因为：

- 它不承担"跨越时间的任务"，只承担"当下这一句话的回答"。
- 它没有"执行到一半"的中间状态需要保护——回答失败了，用户再问一次即可。
- 它不需要"定时触发"，都是用户主动发起。
- 它的失败成本极低（重新问一遍），不需要重试、死信、恢复这些重型机制。

### 3.2 企业 Agent 的世界：有状态、长生命周期、高可靠

企业级 Agent 承担的是**真实业务流程**，而业务流程天生是"长的、有状态的、要求可靠的"。对比一下：

| 维度 | ChatBot | 企业 Long Running Agent |
| --- | --- | --- |
| 生命周期 | 秒级（一次请求） | 小时/天/周级 |
| 状态 | 无状态（或仅会话历史） | 强状态（执行进度、中间产物） |
| 失败处理 | 用户重问 | 自动重试 / Checkpoint 恢复 / DLQ |
| 触发方式 | 用户主动 | 定时 / 事件 / 外部回调 |
| 等待能力 | 不支持 | Suspend / Resume（等审批、等回调） |
| 可观测 | 日志足够 | Monitor + Metrics + Trace + 状态机 |
| 部署形态 | 无状态服务 | 有状态运行时（需要持久化 + 高可用） |
| 失败成本 | 极低 | 极高（Token 成本 + 业务损失） |

**企业为什么必须需要？** 因为企业要把 Agent 用于：财务对账、自动化审批、7×24 舆情监控、AI 客服工单流转、自动化交易、AI Coding 的长任务构建。这些场景一旦失败、丢状态、重复执行，轻则浪费成本，重则造成资损与合规事故。所以企业不是"想要"Long Running Agent，而是"没有它就无法上生产"。

---

## 四、完整知识体系

Day12 覆盖的完整知识地图（每一项都会在对应章节深入讲解并给出可运行 Java 代码）：

```
Long Running Agent
├── Agent Runtime          运行时：调度、执行、生命周期总控
├── Agent Session          会话：一次长任务的实例与身份
├── Agent Lifecycle        生命周期：状态机（CREATED→RUNNING→SUSPENDED→...）
├── State Persistence      状态持久化：把内存状态落库（Redis/PostgreSQL）
├── Checkpoint             检查点：执行到某步时的可恢复快照点
├── Snapshot               快照：某一时刻的完整状态镜像
├── Recovery               恢复：崩溃后从最近 Checkpoint 重建
├── Resume / Suspend       恢复执行 / 挂起
├── Heartbeat              心跳：判活与租约续期
├── Event Driven / Event Bus  事件驱动 / 事件总线
├── Scheduler              调度器：定时（Cron）与延迟触发
├── Task Queue             任务队列：待执行任务的缓冲与并发控制
├── Retry                  重试：失败后按策略再次执行
├── Timeout                超时：防止任务无限期挂死
├── Dead Letter Queue      死信队列：多次重试仍失败的兜底
├── State Machine          状态机：约束合法的状态流转
├── Monitor / Metrics      监控与指标
└── Agent Log              结构化日志与审计
```

这套知识体系并非 Agent 领域独创，而是**分布式系统与工作流引擎数十年沉淀的最佳实践**在 AIAgent 上的应用。理解它，你就能把任何"一次性 Agent"升级为"生产级 Agent"。

---

## 五、核心架构与代码讲解

完整架构见 [ARCHITECTURE.md](./ARCHITECTURE.md)。这里给出模块与包结构总览：

```
day12longrunningagent/
├── runtime/          AgentRuntime、AgentExecutor（执行引擎、总控）
├── session/          AgentSession、AgentSessionStore（会话与身份）
├── lifecycle/        AgentState 枚举、AgentStateMachine（状态机）
├── state/            AgentContext、StatePersistence（上下文与持久化）
├── checkpoint/       Checkpoint、CheckpointStore、SnapshotService
├── recovery/         RecoveryService（崩溃恢复 / Resume）
├── scheduler/        AgentScheduler（Cron/延迟调度）
├── queue/            TaskQueue、DeadLetterQueue
├── retry/            RetryPolicy、RetryExecutor、TimeoutGuard
├── event/            AgentEvent、EventBus、EventListener
├── monitor/          AgentMonitor、AgentMetrics、结构化日志
├── api/              RuntimeController（Runtime 管理 REST API）
├── example/          GithubTrendingAgent（综合实战示例）
└── docs/             README / ARCHITECTURE / TODO / chapters
```

**设计原则**：

1. **接口先行、实现可插拔**：`CheckpointStore`、`AgentSessionStore`、`TaskQueue` 全部面向接口，默认提供内存实现（便于本地跑通），可平滑替换为 Redis / PostgreSQL 实现（生产级）。
2. **状态机驱动**：所有状态流转必须经过 `AgentStateMachine` 校验，杜绝非法流转（如从 `COMPLETED` 跳回 `RUNNING`）。
3. **事件驱动解耦**：Runtime 通过 `EventBus` 广播生命周期事件，Monitor / Log / 业务回调都是监听者，互不耦合。
4. **每一步幂等**：结合 Checkpoint 的 step 编号，保证恢复后不重复执行已完成的步骤（幂等是长任务可靠性的基石）。

---

## 六、运行步骤

> 本模块默认采用**内存实现**，无需任何外部依赖即可跑通，方便学习验证；生产实现（Redis/PostgreSQL）在章节中给出接入方式。

1. 确认 JDK 21、Maven 已安装：`java -version` / `mvn -v`。
2. 在项目根目录编译：`mvn -q -DskipTests compile`。
3. 启动应用：`mvn spring-boot:run`（或运行 `AgentStudyApplication`）。
4. 通过 REST API 创建并启动一个长任务 Agent：

```bash
# 创建并启动 GitHub Trending Agent（示例）
curl -X POST http://localhost:8080/api/day12/runtime/agents \
  -H "Content-Type: application/json" \
  -d '{"type":"github-trending","cron":"0 0 9 * * *"}'

# 查看某个 Agent 的运行状态
curl http://localhost:8080/api/day12/runtime/agents/{sessionId}

# 手动触发一次执行（用于演示，不必等到 9 点）
curl -X POST http://localhost:8080/api/day12/runtime/agents/{sessionId}/trigger

# 挂起 / 恢复
curl -X POST http://localhost:8080/api/day12/runtime/agents/{sessionId}/suspend
curl -X POST http://localhost:8080/api/day12/runtime/agents/{sessionId}/resume
```

---

## 七、效果展示

一次典型的执行会在日志中呈现完整的生命周期轨迹：

```
[Runtime] session=abc123 state CREATED -> RUNNING
[Executor] step=1 login-github ... OK  (checkpoint saved: cp-1)
[Executor] step=2 fetch-trending ... OK (checkpoint saved: cp-2)
[Executor] step=3 summarize (LLM) ... OK (checkpoint saved: cp-3)
[HITL] step=4 need approval -> state RUNNING -> SUSPENDED
... (进程重启 / 等待审批) ...
[Recovery] session=abc123 restored from cp-3, state SUSPENDED
[Executor] approval received -> RESUMED, step=5 send-wecom ... OK
[Runtime] session=abc123 state RUNNING -> WAITING (next fire at 09:00 tomorrow)
```

---

## 八、如何接入 Planning / Browser / Workflow / Memory / HITL

Long Running Agent 是 ZeroHub 平台的"运行时底座"，前面所有能力都可作为它的一个"执行步骤（Step）"接入：

- **Planning Agent（Day10）**：作为 `step=plan`，产出任务计划并写入 `AgentContext`，每完成一步打 Checkpoint。
- **Browser Agent（Day09）**：作为 `step=browse`，Playwright 抓取数据；浏览器会话本身也是一种需要恢复的长状态。
- **Workflow（Day06）**：Runtime 是"跨请求、可持久化"的 Workflow 引擎；单次 Workflow 是 Runtime 内的一段同步执行。
- **Memory（Day04）**：`AgentContext` 的持久化与长期记忆天然融合，恢复时一并加载。
- **Human-in-the-loop（Day11）**：通过 `SUSPENDED` 状态 + 审批事件（`ApprovalEvent`）实现"暂停等待人工"。

接入方式统一为：实现一个 `AgentStep`，由 `AgentExecutor` 顺序/条件调度，每步前后自动打 Checkpoint。

---

## 九、未来如何扩展

- **多节点 Runtime**：多实例通过 Redis 分布式锁 + 租约抢占 Session，实现水平扩展。
- **分布式调度**：用 Redis ZSet 或 Quartz 集群模式替换单机 Scheduler。
- **Agent Failover**：节点宕机后，其持有的 Session 租约过期，其他节点接管并从 Checkpoint 恢复。
- **Kubernetes 部署**：StatefulSet + PVC 持久化，配合 readiness/liveness 探针实现自愈。
- **可观测性**：接入 Micrometer + Prometheus + Grafana + OpenTelemetry Trace。

---

## 十、企业最佳实践

1. **Checkpoint 粒度要恰当**：太频繁伤性能，太少丢进度多。以"不可重复的副作用操作前后"为界打点。
2. **一切副作用必须幂等**：发消息、写库、扣款，恢复后可能重放，务必用幂等键去重。
3. **状态流转必须经状态机**：禁止裸改状态字段，杜绝非法流转。
4. **重试要有上限并落 DLQ**：无限重试会拖垮系统，达到上限进入死信人工介入。
5. **超时必须兜底**：任何外部调用都要有 Timeout，防止 Session 永久挂死。
6. **心跳 + 租约防脑裂**：多节点下用租约保证同一 Session 同一时刻只被一个节点执行。
7. **结构化日志 + TraceId**：以 sessionId 贯穿全链路，问题可追溯。
8. **监控四个黄金指标**：运行中/挂起/失败数、平均步耗时、重试率、恢复次数。
9. **区分"业务失败"与"系统失败"**：前者不该重试（如参数非法），后者才重试（如网络抖动）。
10. **恢复要防重复恢复**：用租约/幂等标记避免多个节点同时恢复同一 Session。

---

## 十一、课后总结

今天我们建立了对 Long Running Agent 的系统认知：它是 LLM 智能与"操作系统级生命周期管理 + 工作流引擎级持久化恢复"的结合体。我们理清了它与 ChatBot 的本质区别、企业为何必须需要，并搭建了包含 Runtime / Session / Lifecycle / Checkpoint / Recovery / Scheduler / Queue / Retry / Event / Monitor 的完整模块骨架，最终以"GitHub Trending 每日巡检 Agent"作为综合实战。

至此，ZeroHub AI Agent Platform 完成能力升级：

```
LLM → Memory → RAG → Workflow → MCP → Multi-Agent
    → Browser Agent → Planning Agent → Human-in-the-loop → Long Running Agent
```

为 Day13 的 AI Office Agent 打下了完整的运行时基础。

---

## 十二、章节导航

| 章节 | 主题 |
| --- | --- |
| [chapter-01](./chapters/chapter-01.md) | 为什么需要 Long Running Agent（理论与商业价值） |
| [chapter-02](./chapters/chapter-02.md) | Agent Runtime 与 Lifecycle 状态机 |
| [chapter-03](./chapters/chapter-03.md) | Session 与 State 持久化 |
| [chapter-04](./chapters/chapter-04.md) | Checkpoint / Snapshot / Recovery |
| [chapter-05](./chapters/chapter-05.md) | Scheduler / Task Queue / Retry / Timeout / DLQ |
| [chapter-06](./chapters/chapter-06.md) | Event Bus 与事件驱动 |
| [chapter-07](./chapters/chapter-07.md) | Monitor / 日志 / 可观测性 |
| [chapter-08](./chapters/chapter-08.md) | 综合实战：GitHub Trending Long Running Agent |

> 学习方式：按章节顺序学习，每章包含"为什么学 / 是什么 / 怎么用 / 真实项目 / 避坑"五部分，代码均可运行。