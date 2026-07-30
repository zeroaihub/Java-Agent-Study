# Day12 · Long Running Agent · TODO

> 难度分级：⭐ 必做（掌握核心） · ⭐⭐ 进阶（生产可用） · ⭐⭐⭐ 企业挑战（分布式高可用）

---

## ⭐ 必做（Must Do）

- [x] ⭐ 实现 `AgentState` 枚举与 `AgentStateMachine`（合法流转校验）
- [x] ⭐ 实现 `AgentSession`（长任务实例与身份、元数据）
- [x] ⭐ 实现 `AgentContext`（执行进度 step 指针 + 中间产物 KV）
- [x] ⭐ 实现 `TaskHandler` 抽象与 `GithubTrendingHandler` 多步流水线
- [x] ⭐ 实现单步驱动执行（逐步执行、异常捕获、状态推进）
- [x] ⭐ 实现 `AgentRuntime`（创建/启动/流转/打点总控，一致动作序列）
- [x] ⭐ 实现内存版 `SessionStore` 与 `CheckpointManager`
- [x] ⭐ 每步完成后自动打 `Checkpoint`
- [x] ⭐ 实现 `AgentApiController`（REST：trigger/get session/metrics/dlq）
- [x] ⭐ 跑通"手动触发一次执行 → 打点 → 完成"的最小闭环

---

## ⭐⭐ 进阶（Advanced，生产可用）

- [x] ⭐⭐ 实现 `CheckpointManager`（状态快照）与 `RecoveryService`（崩溃恢复）
- [ ] ⭐⭐ 实现 `Suspend/Resume`：结合 `SUSPENDED` 状态支持 HITL 审批挂起
- [x] ⭐⭐ 实现 `RetryPolicy`（指数退避 + 抖动 + 可重试异常判定）
- [x] ⭐⭐ 实现 `TaskDispatcher` 重试与失败判定（配合超时保护）
- [x] ⭐⭐ 实现 `TaskQueue` 与 `DeadLetterQueue`
- [x] ⭐⭐ 实现 `AgentScheduler`（Cron 定时 + 消费心跳）
- [x] ⭐⭐ 实现 `EventBus` + `AgentEvent` 事件驱动，Monitor 作为监听者
- [x] ⭐⭐ 实现 `MonitorEventListener` + `AgentMetrics`（运行中/失败/重试/死信指标）
- [x] ⭐⭐ 结构化日志：sessionId 贯穿全链路
- [ ]⭐⭐ 接入 Redis 实现 `Session/Checkpoint` 持久化（替换内存实现）
- [ ] ⭐⭐ 接入 PostgreSQL 存储 Checkpoint 历史与审计
- [ ] ⭐⭐ 保证副作用步骤幂等（幂等键去重）

---

## ⭐⭐⭐ 企业挑战（Enterprise Challenge，分布式高可用）

- [ ] ⭐⭐⭐ 多节点 Runtime：Redis 分布式锁 + 租约抢占 Session
- [ ] ⭐⭐⭐ Heartbeat 心跳与租约续期，检测节点存活
- [ ] ⭐⭐⭐ 分布式调度：Redis ZSet 延迟队列 / Quartz 集群模式
- [ ] ⭐⭐⭐ Agent Failover：节点宕机后其他节点接管并从 Checkpoint 恢复
- [ ] ⭐⭐⭐ 防重复恢复：租约 + 幂等标记避免多节点同时恢复同一 Session
- [ ] ⭐⭐⭐ Kubernetes 部署：StatefulSet + PVC + liveness/readiness 探针
- [ ] ⭐⭐⭐ 可观测性：Micrometer + Prometheus + Grafana + OpenTelemetry Trace
- [ ] ⭐⭐⭐ 背压与限流：Task Queue 容量控制 + 并发信号量
- [ ] ⭐⭐⭐ 灰度与版本化：Step 版本化，支持 Agent 定义热升级
- [ ] ⭐⭐⭐ 成本治理：LLM Token / 步骤耗时统计与预算熔断

---

## 综合实战验收标准

- [x] "GitHub Trending 每日巡检 Agent" 可通过 API 创建
- [x] 可手动触发一次执行，日志呈现完整生命周期
- [ ] 在 `summarize` 后进入 `SUSPENDED` 等待审批，审批后 `RESUME`继续
- [x] 模拟进程重启，能从最近 Checkpoint 恢复继续执行
- [x] 模拟某步失败，触发重试；超过上限进入 DLQ
- [ ] 本轮完成后进入 `WAITING`，等待下一次 Cron 触发