# Day08 Multi-Agent TODO 清单

> 分三档：⭐ 必做（跑通基础）、⭐⭐ 进阶（加强能力）、⭐⭐⭐ 企业挑战（生产级）。
> 建议按顺序完成，每完成一项在方框内打勾。

---

## ⭐ 必做（基础闭环，本训练营今日目标）

- [ ] 设计并实现 `Agent` 接口 + `AbstractAgent` 抽象基类（统一日志/计时/异常兜底）
- [ ] 实现 `AgentContext`、`AgentResult`、`AgentRole`
- [ ] 实现 `Task`（任务）与 `Message`（消息）模型
- [ ] 实现 `SharedMemory`（线程安全的共享黑板）
- [ ] 实现 `PlannerAgent`：产出文章大纲
- [ ] 实现 `ResearchAgent`：为大纲每节收集素材
- [ ] 实现 `WriterAgent`：根据大纲+素材写正文
- [ ] 实现 `ReviewerAgent`：审校 + 打分 + 意见
- [ ] 实现 `AgentManager`（Agent 花名册）与 `Coordinator`（顺序编排）
- [ ] 实现可插拔 `LlmClient` + 默认 `MockLlmClient`（开箱即运行）
- [ ] 实现 `ContentController` + `ContentService`，跑通一次完整内容生成
- [ ] 记录并返回全链路 `AgentExecutionLog`

---

## ⭐⭐ 进阶（提升协作与质量）

- [ ] 反思闭环：Reviewer 打分低于阈值时，Coordinator 把意见回传 Writer 重写（限制 maxRounds 防死循环）
- [ ] 并行调研：把大拆成多节，多个 ResearchAgent 用线程池并行执行后聚合
- [ ] 为 Coordinator 增加“协同策略”抽象（Sequential / Parallel / Reflection 可切换）
- [ ] 接入真实 LLM：新增基于 Spring AI 的 `OpenAiLlmClient`，配置 Key 后切换
- [ ] SharedMemory 支持版本快照，便于回放调试
- [ ] 增加单元测试：Coordinator 编排顺序、SharedMemory 并发安全

---

## ⭐⭐⭐ 企业挑战（生产级）

- [ ] 整合 Day07 MCP：ResearchAgent 通过 MCP Client 调真实工具（搜索/天气），形成 Agent→MCP→Tool 链路
- [ ] 整合 Day06 Workflow：用 Workflow 编排「Coordinator 节点」，实现 Workflow→Coordinator→多Agent→MCP
- [ ] 事件驱动：把 SharedMemory 升级为消息总线（Kafka/Redis），支持跨进程 Agent 集群
- [ ] 扩展到 100+ Agent：Agent 注册中心 + 按能力路由 + 负载均衡
- [ ] Agent 权限治理：每个 Agent 的工具白名单、Token 预算、超时熔断
- [ ] 可观测性：接入 Micrometer/日志系统，统计每个 Agent 的耗时、Token、成功率
- [ ] 成本控制：Token 计费、缓存命中、重复任务去重
- [ ] Agent 版本管理：Prompt/模型版本灰度与回滚
- [ ] 防推诿机制：明确每个 Agent 的输入契约与失败上报，Coordinator 兜底重试或降级

---

## 思考题（阶段性验收）

- 第一章后：Multi-Agent 与传统微服务的相同点和不同点？
- 第二章后：画出本项目的完整架构图（含五大角色 + 消息流）。
- 项目完成后：如果 Reviewer 和 Writer 无限互相打回，你会用哪些手段止损？