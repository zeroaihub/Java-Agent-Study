# Day11 · TODO（练习任务清单）

> 分三档：⭐ 必做（打基础）｜⭐⭐ 进阶（工程化）｜⭐⭐⭐ 企业挑战（可落地）
> 每个任务都能真正动手练。建议按顺序完成。

---

## ⭐ 必做（Fundamentals）

- [ ] T1. 定义领域模型：`ApprovalRequest` / `ApprovalDecision` / `ApprovalStatus`（枚举）。
- [ ] T2. 实现 `ApprovalStateMachine`，非法流转抛异常（PENDING→APPROVED/REJECTED/MODIFIED/TIMEOUT）。
- [ ] T3. 实现单级 `ApprovalEngine`：create / approve / reject，内存存储先跑通。
- [ ] T4. 实现 `RiskPolicy`：命中关键词（delete/批量/transfer/支付）判为高风险。
- [ ] T5. 实现 `ApprovalGate.guard(action)`：命中风险→挂起→等待；否则直接放行。
- [ ] T6. 写一个可运行的 Demo：模拟"删除测试订单"触发审批，控制台批准后继续执行。

## ⭐⭐ 进阶（Engineering）

- [ ] T7. 实现 `InterruptManager`：挂起任务但**不阻塞线程**（状态 + 待办登记）。
- [ ] T8. 实现 `CheckpointManager`：先写内存版，再加 Redis + PostgreSQL 双写，带 version/checksum。
- [ ] T9. 实现 `ResumeEngine`：从 Checkpoint 恢复上下文并续跑，保证**幂等**（同请求只恢复一次）。
- [ ] T10. 实现 `Reject → Retry`：驳回后允许人工修改参数后重新执行（Modify Task）。
- [ ] T11. 实现审批**超时兜底**：定时扫描 PENDING，超时按策略 TIMEOUT（默认拒绝/升级）。
- [ ] T12. 实现 `Approval API`（REST）：待审列表 / 批准 / 驳回 / 修改 / 详情。
- [ ] T13. 实现简化 `Approval UI`（单页 HTML）：轮询待审列表，点击批准/驳回。
- [ ] T14. 全链路 `AuditLog`：谁、何时、对哪个请求、做了什么决策，全部落库。

## ⭐⭐⭐ 企业挑战（Enterprise）

- [ ] E1. **多级审批**：按金额/风险等级动态生成审批链（L1/L2/L3），任一级驳回则整体驳回。
- [ ] E2. **人工修改执行计划**：接入 Planning Agent，人可在审批时直接编辑计划再执行。
- [ ] E3. **企业审批中心**：审批任务列表 / 状态 / 历史 / 日志 / 恢复 / 权限（RBAC）/ 通知。
- [ ] E4. **Feedback Learning**：同类低风险动作连续 N 次通过后，自动生成放行策略、降低人工。
- [ ] E5. **多通道通知**：抽象 `Notifier` 接口，预留企业微信 / 飞书 / 钉钉 / 邮件 / Webhook。
- [ ] E6. **幂等 & 分布式锁**：用 Redis 锁防止重复审批、重复恢复导致的重复执行。
- [ ] E7. **审批 SLA 看板**：统计平均审批时长、超时率、各审批人负载。
- [ ] E8. **接入 Browser Agent**：危险 DOM 动作（删除/提交/支付）执行前统一走 ApprovalGate。

---

## 验收标准（Definition of Done）

1. `day11humanintheloop` 模块可**独立编译、独立运行**，不改动任何前十天代码。
2. 能完整跑通 README 第 5 节的"ERP 删单"故事：挂起→审批→恢复→审计。
3. 所有状态流转经过状态机校验，无脏状态。
4. 审批具备超时兜底与幂等保护。
5. 有可访问的审批中心页面与 REST API。
6. 代码符合企业规范：分层清晰、无魔法值、有日志、有异常处理。

---

## 自检清单（避坑）

- [ ] 审批是否可能无限等待？（必须有超时）
- [ ] 重复点击批准会不会重复执行？（必须幂等）
- [ ] 挂起是否占用线程？（不应阻塞线程池）
- [ ] Checkpoint 和业务动作顺序对吗？（先快照后执行）
- [ ] 恢复用的是不是正确的快照？（version/checksum 校验）
- [ ] 决策有没有全部落审计日志？
- [ ] 谁能审、能审多大，有没有权限校验？