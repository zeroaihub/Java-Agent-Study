# 第七章：从「能跑」到「平台级」（企业级 Workflow 设计）

> 前六章造出了一个健壮的引擎。本章跳出代码，站在架构师视角回答一个问题：
> **一个能支撑上百个业务流程、多人协作、7×24 运行的 Workflow 平台，还差什么？**

---

## 第一部分：为什么学（核心价值）

单个 Demo 和「平台」的区别，不在于代码写得多花哨，而在于四种能力：
**可编排、可演进、可干预、可观测**。
面试中「你怎么设计一个 Workflow 平台」考的就是这四点，而非某个 API 用法。

---

## 第二部分：是什么（四大企业级支柱）

### 1. 配置化编排（Orchestration）
流程不该硬编码在 Java 里。理想形态：用 JSON/YAML/DB 描述「节点 + 连接 + 条件」，
引擎读配置动态组装。业务改流程无需改代码、无需发版。

```yaml
workflow: travel-plan
nodes: [inputCity, weather, hotel, plan, output]
edges:
  - {from: weather, to: hotel, when: SUCCESS}
  - {from: weather, to: output, when: FAILED}   # 条件分支
```

本项目当前用 `TravelWorkflowConfig` 的 `List` 硬编码——这是「配置化」的最简雏形，
下一步就是把这个 List 搬到外部配置。

### 2. 版本管理（Versioning）
流程会迭代。v1 已在跑的订单，不能因为发布 v2 就断掉。
需要：流程定义带版本号，运行实例绑定它启动时的版本，新老版本并存。

### 3. Human-in-the-Loop（人工介入）
高风险节点（大额支付、法律审批）需要人来确认。
这正是 `NodeStatus.SUSPENDED` 的用途：节点返回 SUSPENDED → 引擎挂起 → 存储现场 →
等人工审批回调 → 从断点恢复。

### 4. 可观测性（Observability）
不只有第六章的执行日志，平台级还需要：
- **Metrics**：各流程成功率、P99 耗时、各节点失败率（接 Prometheus）；
- **Tracing**：一次运行的全链路 traceId（接 SkyWalking/Zipkin）；
- **持久化**：Context 与日志落库，支持事后回溯与断点恢复。

---

## 第三部分：怎么用（本项目的映射与延伸）

| 企业级能力 | 本项目现状 | 生产演进方向 |
|---|---|---|
| 配置化排 | `TravelWorkflowConfig` 硬编码 List | 外置 YAML/DB + 动态加载 |
| 状态机 | `WorkflowState` 枚举 | 状态持久化到 DB |
| 人工介入 | `SUSPENDED` 状态已预留 | 加挂起存储 + 恢复接口 |
| 可观测 | `WorkflowExecutionLog` 内存日志 | 落库 + Metrics + Tracing |
| 重试兜底 | `executeWithRetry` | + 退避 + 超时 + 熔断 |

**关键认知**：本项目每个「简化实现」都对应一个真实的企业级扩展点。
架构的价值在于——预留了扩展点，而不是一次做满。

---

## 第四部分：Python 参考

生产级 Python 方案的能力对照：
- **配置化/可视化编排**：Dify、n8n、Flowise（拖拽即编排）；
- **状态持久化 + 断点恢复**：LangGraph 的 checkpointer（存 Redis/Postgres）；
- **Human-in-the-loop**：LangGraph 的 `interrupt()` + `Command(resume=...)`；
- **可观测**：LangSmith 全链路追踪。

它们与本项目是同构的：**节点 + 状态机 + 持久化 + 可观测**，只是成熟度不同。

---

## 第五部分：用在哪 + 避坑优化

**用在哪**：任何需要长期运行、多团队维护、流程频繁变更的业务中台。

**架构避坑**：
1. **过度设计**：初期就上分布式引擎 + 可视化编排，投入产出比极低。**从硬编码 List 起步，按需演进**（本项目的路径）。
2. **状态只存内存**：进程重启即丢失，无法断点恢复。核心状态必须持久化。
3. **把 LLM 调用写进引擎**：引擎应与「节点里做什么」无关，LLM 只是某个节点的实现细节。
4. **忽视幂等**：断点恢复 / 重试的前提是节点幂等，否则恢复即重复执行。

---

## 面试问题

1. 如何设计一个支持「运行中流程」的版本升级而不影响存量实例？
2. `SUSPENDED` 状态在 Human-in-the-loop 场景中如何配合持久化实现断点恢复？
3. 配置化编排相比硬编码流程，牺牲了什么、换来了什么？
4. 为什么说「架构的价值是预留扩展点，而非一次做满」？

---

## 练习答案（参考）

> 练习：把 `TravelWorkflowConfig` 的硬编码 List 改造成「从配置读取节点名再组装」。
> 参考思路：
> 1. 所有节点 `@Component` 注册，Spring 用 `Map<String, WorkflowNode>` 按 beanName 收集；
> 2. 从 `application.yml` 读 `workflow.travel.nodes: [inputCityNode, weatherNode, ...]`；
> 3. 按配置里的名字顺序从 Map 取出节点组成 List。
> 这样改流程只改 yml，代码零改动——迈出「配置化编排」第一步。

---

> 下一章（终章）：把 config → service → controller 三层串起来，跑通完整的
> Travel Agent，给出访问方式与预期输出——你将看到前七章所有概念汇成一个可访问的接口。