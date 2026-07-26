# Day08 Multi-Agent 架构文档（ARCHITECTURE.md）

> 本文说明 AI 内容生产平台 V1 的系统架构、Agent 关系、消息流、协作流程、模块划分、未来扩展，以及“为什么这样设计”。

---

## 1. 系统架构（分层）

```
┌──────────────────────────────────────────────────────────┐
│  接入层  Controller                                        │
│  ContentController：POST /api/day08/content/generate       │
└───────────────────────────┬──────────────────────────────┘
                            │ DTO(ContentRequest)
                            ▼
┌──────────────────────────────────────────────────────────┐
│  应用层  Service                                           │
│  ContentService：组装 Task，调用 Coordinator，封装响应       │
└───────────────────────────┬──────────────────────────────┘
                            │ Task
                            ▼
┌──────────────────────────────────────────────────────────┐
│  编排层  Coordinator（Supervisor / 项目经理）               │
│  按顺序编排：Planner → Research → Writer → Reviewer          │
│  维护 SharedMemory、记录 AgentExecutionLog、控制轮次上限      │
└───┬───────────────┬───────────────┬───────────────┬───────┘
    ▼               ▼               ▼               ▼
┌────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│Planner │    │ Research │    │  Writer  │    │ Reviewer │   Agent 层
│ Agent  │    │  Agent   │    │  Agent   │    │  Agent   │
└───┬────┘    └────┬─────┘    └────┬─────┘    └────┬─────┘
    │              │               │               │
    └──────────────┴───────┬───────┴───────────────┘
                          ▼
              ┌───────────────────────┐
              │   SharedMemory(黑板)    │   记忆层
              │  outline/materials/    │
              │  draft/review/logs     │
             └───────────────────────┘
                          │ (可选)
                          ▼
              ┌───────────────────────┐
              │  LlmClient (可插拔)     │   能力层
              │  Mock / OpenAI 实现     │
              └───────────────────────┘
```

**分层理由**：Controller 只管协议转换，Service 只管用例编排入口，真正的“多 Agent 智能编排”下沉到 Coordinator。这样即使以后换成 Kafka 事件驱动或 gRPC 跨进程，上层接口不变。

---

## 2. Agent 关系图

```
                 ┌──────────────┐
                 │ AgentManager │  注册 & 按角色查找所有 Agent（花名册）
                 └──────┬───────┘
                        │ 提供 Agent 列表
                        ▼
                 ┌──────────────┐
                 │ Coordinator  │  依赖抽象 Agent 接口，不依赖具体实现
                 └──────┬───────┘
                        │ 面向接口调用 execute()
          ┌─────────────┼─────────────┬──────────────┐
          ▼             ▼             ▼              ▼
    PlannerAgent  ResearchAgent  WriterAgent   ReviewerAgent
       (都 implements Agent，都继承 AbstractAgent)
```

- 所有 Agent 实现统一的 `Agent` 接口，继承 `AbstractAgent`（模板方法：统一日志、计时、异常兜底）。
- `Coordinator` 只依赖 `Agent` 抽象与 `AgentManager`，**符合依赖倒置原则（DIP）**。
- 新增 Agent（如 SeoAgent）：新建类 implements Agent，交给 Spring，`AgentManager` 自动收集，`Coordinator` 无需改动（**开闭原则 OCP**）。

---

## 3. 消息流（一次内容生成）

```
Controller.generate(req)
   → Service 组装 Task{topic, requirement}
      → Coordinator.execute(task)
          ① Planner.execute(ctx)   写 SharedMemory["outline"]
          ② Research.execute(ctx)  读 outline，写 SharedMemory["materials"]
          ③ Writer.execute(ctx)    读 outline+materials，写 SharedMemory["draft"]
          ④ Reviewer.execute(ctx)  读 draft，写 SharedMemory["review","score"]
          ⑤ Coordinator 汇总：读 draft/review/score/logs
      → Service 封装 ContentResponse
   → Controller 返回 JSON（含文章 + 执行日志）
```

每一步都产生一条 `AgentExecutionLog`（agentName、输入摘要、输出摘要、耗时、状态），构成**全链路可观测**。

---

## 4. 协作流程（时序）

```
User    Coordinator   Planner   Research   Writer   Reviewer   SharedMemory
 │  req      │           │          │         │         │           │
 ├──────────▶│           │          │         │         │           │
 │           ├──execute─▶│          │         │         │           │
 │           │           ├─write outline──────────────────────────▶│
 │           │◀──done────┤          │         │         │           │
 │           ├──execute────────────▶│         │         │           │
 │           │           │          ├─read outline / write materials▶│
 │           │◀──done───────────────┤         │         │           │
 │           ├──execute──────────────────────▶│         │           │
 │           │           │          │         ├─write draft────────▶│
 │           │◀──done─────────────────────────┤         │           │
 │           ├──execute────────────────────────────────▶│           │
 │           │           │          │         │         ├─write review▶│
 │           │◀──done───────────────────────────────────┤           │
 │◀─result───┤ 汇总 draft+review+logs                                │
```

---

## 5. 模块图

| 包 | 职责 | 关键类 | 设计原则 |
|----|------|--------|---------|
| `agent.core` | Agent 抽象与契约 | `Agent`、`AbstractAgent`、`AgentContext`、`AgentResult`、`AgentRole` | SRP / 模板方法 |
| `agent.message` | 任务与消息模型 | `Task`、`Message` | 数据与行为分离 |
| `agent.memory` | 共享记忆（黑板） | `SharedMemory` | 单一数据源 |
| `agent.planner/research/writer/reviewer` | 四个具体 Agent | `*Agent` | OCP |
| `agent.coordinator` | 编排大脑 | `Coordinator`、`AgentManager` | DIP |
| `config` | LLM 客户端等配置 | `LlmClient`、`MockLlmClient` | 可插拔 |
| `dto` | 出入参 | `ContentRequest`、`ContentResponse` | 防腐层 |
| `entity` | 领域实体 | `AgentExecutionLog` | — |
| `controller/service` | 接入与用例编排 | `ContentController`、`ContentService` | 分层 |

---

## 6. 未来扩展

- **反思闭环**：Reviewer 打分 < 阈值时，Coordinator 自动把意见回传 Writer 重写，循环 N 轮（受 `maxRounds` 保护，防死循环）。
- **并行调研**：把大纲拆成多节，多个 ResearchAgent 并行调研（`CompletableFuture` + 线程池，可复用 Day 现有 `ThreadPoolConfig`）。
- **接入 MCP**：ResearchAgent 通过 Day07 的 MCP Client 调用真实搜索/天气等工具，形成 `Agent → MCP → Tool` 调用链。
- **事件驱动**：SharedMemory 升级为消息总线（Kafka），支持跨进程、跨机器的 Agent 集群。
- **权限与治理**：为每个 Agent 增加可调用工具白名单、Token 预算、超时熔断。
- **真实 LLM**：把 `MockLlmClient` 换成基于 Spring AI 的 `OpenAiLlmClient`，配置 Key 即可切换。

---

## 7. 为什么这样设计

1. **为什么用 Coordinator 中心化而不是 Agent 直接互相调用？**
   中心化编排让流程可见、可控、可审计，避免 Agent 之间形成难以追踪的调用网和死循环。企业首选“主管模式”正是为了可观测和可治理。

2. **为什么用 SharedMemory（黑板）而不是直接传参？**
   黑板解耦了 Agent 之间的依赖：Writer 不需要知道素材是哪个 Agent 产生的，只需从黑板读 `materials`。同时黑板天然是“执行快照”，便于调试和回放。

3. **为什么先做顺序流水线，而不是一上来就并行/反思？**
   内容生产有天然先后依赖（先大纲后正文）。先把最简、最可验证的顺序版跑通，再逐步加并行、反思、MCP——符合“可运行优先、逐步演进”的工程原则。

4. **为什么 LLM 客户端要可插拔？**
   保证项目开箱即运行（Mock 实现无需 Key），同时不锁死厂商，配置真实 Key 即可切换，符合依赖倒置与面向接口编程。

5. **为什么每一步都记日志？**
   Multi-Agent 最大的运维痛点是“黑盒、难排查”。全链路 `AgentExecutionLog` 是可观测性的地基，也是后续做成本核算、性能优化的数据来源。