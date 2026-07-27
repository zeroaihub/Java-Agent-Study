# Day10 Planning Agent 练习任务（TODO）

> 分三档：⭐ 必做（掌握核心闭环）｜⭐⭐ 进阶（企业级健壮性）｜⭐⭐⭐ 企业挑战（生产级能力）。
> 每个任务都可真正编码练习，建议按顺序完成。

---

## ⭐ 必做（Planning Agent 最小可用闭环）

- [ ] **T1 领域模型**：实现 `Goal` / `Plan` / `PlanStep` / `PlanState`，明确字段与不变式（chapter-03）。
- [ ] **T2 简单 Planner**：实现 `LlmPlanner.plan(goal)`，让 LLM 输出结构化 JSON 计划并解析成 `Plan`（chapter-03）。
- [ ] **T3 上下文黑板**：实现 `PlanningContext`（Blackboard），支持写观察、读历史（chapter-04）。
- [ ] **T4 调度器**：实现 `PlanScheduler`，按 `dependsOn` + `priority` 每轮挑出就绪步骤（chapter-04）。
- [ ] **T5 工具注册与选择**：实现 `ToolSpec` / `ToolRegistry` / `ToolSelector`，从白名单选工具（chapter-07）。
- [ ] **T6 执行器**：实现 `PlanExecutor.execute(step)`，调用工具、产出 `StepResult`（chapter-05）。
- [ ] **T7 主循环**：`PlanningService` 串起「规划→调度→执行→写观察」的最小闭环（chapter-05）。
- [ ] **T8 REST 入口**：`PlanningController` 暴露 `/api/day10/planning/run`，能跑通一句目标（chapter-09）。

---

## ⭐⭐ 进阶（让 Agent 变可靠）

- [ ] **T9 失败重试**：为 `PlanExecutor` 加 `RetryPolicy`（次数 + 退避），可配置（chapter-05）。
- [ ] **T10 反思模块**：实现 `Reflector.reflect(...)`，返回 `SUCCESS/RETRY_STEP/REPLAN/ABORT`（chapter-06）。
- [ ] **T11 自我修正**：反思判 `RETRY_STEP` 时，用反思建议**改参数**后重试同一步（chapter-06）。
- [ ] **T12 动态重规划**：实现 `Planner.replan(context)`，带「已完成成果 + 失败原因」做增量重规划（chapter-08）。
- [ ] **T13 预算护栏**：加 `maxSteps / maxReplan / maxTokens / timeout`，超限进 `FAILED`（chapter-05）。
- [ ] **T14 可观测 Trace**：每步产出结构化 Trace 并收集到 `Plan` 里随响应返回（chapter-09）。
- [ ] **T15 状态机落地**：用枚举 `PlanState` + 显式转移方法，禁止非法状态跳转（chapter-04）。

---

## ⭐⭐⭐ 企业挑战（生产级）

- [ ] **T16 接 Browser Agent**：把 Day9 的浏览器能力封装成 `BrowserTool` 注册进 Registry，跑通「浏览 GitHub Trending」（chapter-09）。
- [ ] **T17 接 Workflow / MCP**：把一条 Day6 Workflow、一个 Day7 MCP 工具各适配成一个 `ToolSpec`（chapter-09）。
- [ ] **T18 规划记忆**：实现 `PlanningMemory`，成功/失败经验落 PG，规划前检索相似历史注入 Prompt。
- [ ] **T19 状态持久化 + 断点续跑**：把 `PlanningContext` 存 Redis，进程重启后能从上次成功步骤恢复。
- [ ] **T20 并行调度**：当就绪步骤有多个且互不依赖时，用线程池并行执行，注意黑板并发安全。
- [ ] **T21 Human-in-the-loop 断点**：给高风险步骤加 `WAITING_HUMAN` 状态与审批接口（为 Day11 铺垫）。
- [ ] **T22 成本优化**：规划用强模型、执行/反思用便宜模型；压缩注入 Prompt 的历史观察（滑窗 + 摘要）。
- [ ] **T23 完整端到端 Demo**：跑通「分析 GitHub Trending 最热 AI Agent 项目 → 生成 Markdown」全自动闭环。

---

## 验收标准

1. 项目可 `mvn spring-boot:run` 独立启动，**不依赖也不修改前九天任何代码**。
2. 调用 `/api/day10/planning/run` 传一句目标，返回完整 `Plan`（含每步 Thought/Action/Observation）+ 最终结果。
3. 人为让某步失败，能观察到「反思 → 重试 / 重规划 → 最终完成或优雅失败」。
4. 所有预算护栏生效：不会无限循环、不会无限烧 Token。