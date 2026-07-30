# Day11 · Human-in-the-loop 架构设计（ARCHITECTURE）

本文件给出 Day11 HITL 模块的**完整架构、全套 ASCII 图、各模块关系与设计原因**。
一切以"能直接落地到 ZeroHub、能上生产"为标准。

---

## 1. 整体系统架构

```
                          ┌──────────────────────────────────────────┐
                          │              ZeroHub Platform              │
                          │                                            │
 用户请求 ──► API 网关 ──► │  Planning Agent (D10)                      │
                          │        │ 生成计划                          │
                          │        ▼                                  │
                          │  执行编排 (Workflow D6 / Multi-Agent D8)   │
                          │        │                                  │
                          │        ▼                                  │
                          │  ┌───────────────────────────────────┐    │
                          │  │        ApprovalGate（审批网关）     │    │  ← Day11 入口
                          │  │  risk = RiskPolicy.evaluate(action)│    │
                          │  └───────────────┬───────────────────┘    │
                          │                  │ 命中风险               │
                          │                  ▼                        │
                          │   ┌──────────────────────────────────┐    │
                          │   │        Human-in-the-loop Core     │    │
                          │   │  ┌────────────┐  ┌─────────────┐  │    │
                          │   │  │ Checkpoint │  │  Interrupt  │  │    │
                          │   │  │  Manager   │  │  Manager    │  │    │
                          │   │  └────────────┘  └─────────────┘  │    │
                          │   │  ┌────────────┐  ┌─────────────┐  │    │
                          │   │  │  Approval  │  │   Resume    │  │    │
                          │   │  │  Engine    │  │   Engine    │  │    │
                          │   │  └────────────┘  └─────────────┘  │    │
                          │   │  ┌────────────┐                   │    │
                          │   │  │  Feedback  │                   │    │
                          │   │  │  Engine    │                   │    │
                          │   │  └────────────┘                   │    │
                          │   └──────────┬───────────────────────┘    │
                          │              │                            │
                          └──────────────┼────────────────────────────┘
                                         │
              ┌──────────────────────────┼──────────────────────────┐
              ▼                          ▼                          ▼
      Approval API (REST)        Approval UI (审批中心)      通知渠道(企业微信/飞书/邮件)
              │                          │
              └────────── 审批人做出决策 ──┘
                                         │
                          ┌──────────────┴───────────────┐
                          ▼                              ▼
                   Redis (待审队列/锁)         PostgreSQL (审批记录/审计日志/检查点)
```

**设计原因**：
- ApprovalGate 作为**唯一入口**，把"是否需要人"这件事收敛到一处，其余模块无感。
- 核心内核（Core）与对外接口（API/UI/通知）**分层解耦**，方便替换 UI 或接入不同 IM。
- Redis 负责**低延迟状态与分布式锁**，PostgreSQL 负责**持久化与审计**，职责清晰。

---

## 2. Agent 生命周期（引入 HITL 后）

```
CREATED ──► PLANNING ──► READY ──► RUNNING ──► [ApprovalGate]
                                                   │
                              ┌────────────────────┼────────────────────┐
                              │ 无风险             │ 命中风险            │
                              ▼                    ▼                     
                          CONTINUE           WAITING_APPROVAL         
                              │                    │                     
                              │        ┌───────────┼────────────┬───────────┐
                              │        ▼           ▼            ▼           ▼
                              │    APPROVED     REJECTED     MODIFIED    TIMEOUT
                              │        │           │            │           │
                              │     RESUMING    ABORTED      RETRYING   (默认策略)
                              │        │                        │
                              └────────┴────► RUNNING ◄─────────┘
                                                   │
                                                   ▼
                                               COMPLETED
```

---

## 3. Approval 生命周期（审批状态机）

```
                   create()
                      │
                      ▼
                 ┌─────────┐
                 │ PENDING │◄──────────────┐ escalate（升级到上级）
                 └────┬────┘               │
        approve()│ reject()│ modify()│ timeout()
                 │        │         │        │
                 ▼        ▼         ▼        ▼
           ┌─────────┐ ┌────────┐ ┌────────┐ ┌────────────┐
           │APPROVED │ │REJECTED│ │MODIFIED│ │  TIMEOUT   │
           └────┬────┘ └────────┘ └───┬────┘ └─────┬──────┘
                │                     │            │
       (多级?) ─┤ 还有下一级          │            │ 默认拒绝/升级
                │ 回到 PENDING(下一级) │            │
                ▼                     ▼            ▼
           ┌─────────┐          重新生成请求     按策略处理
           │  FINAL  │
           │APPROVED │
          └─────────┘

合法流转由 ApprovalStateMachine 强校验：非法流转直接抛异常，杜绝脏状态。
```

---

## 4. Interrupt 流程（挂起）

```
Agent 执行到危险动作
      │
      ▼
ApprovalGate.guard(action)
      │  命中 RiskPolicy
      ▼
CheckpointManager.save(context)   ── 先快照，保证可恢复
      │
      ▼
InterruptManager.interrupt(taskId)
      │  ├─ 标记任务 WAITING_APPROVAL
      │  ├─ 释放/挂起执行线程（不阻塞线程池）
      │  └─ 登记待办到 Redis 待审队列
      ▼
ApprovalEngine.create(request)     ── 生成审批请求
      │
      ▼
通知审批人（API/UI/IM）→ 执行流"冻结"，等待决策
```

**设计原因**：**先 Checkpoint 再 Interrupt**，确保任何时刻挂起都能恢复；
挂起用"状态 + 待办登记"而非"阻塞线程"，避免大量长任务把线程池耗尽（为 Day12 长任务铺垫）。

---

## 5. Resume 流程（恢复）

```
审批人 APPROVE
      │
      ▼
ApprovalEngine.onApproved(requestId)
      │
      ▼
ResumeEngine.resume(taskId)
      │  ├─ CheckpointManager.load(taskId)     ── 取回执行现场
      │  ├─ 校验快照有效性（版本/一致性）
      │  ├─ 恢复上下文并从断点继续
      │  └─ 幂等保护：同一 requestId 只恢复一次
      ▼
继续执行危险动作 ──► 写审计日志 ──► COMPLETED
```

---

## 6. Checkpoint 流程（检查点）

```
save():                         load():
 context ──序列化──► JSON         PG/Redis──► JSON ──反序列化──► context
   │                              │
   ├─ 写 PostgreSQL(持久,审计)     ├─ 校验 checksum / version
   └─ 写 Redis(快速恢复,TTL)       └─ 命中即续跑，未命中则报错并转人工

结构：Checkpoint{ id, taskId, stepIndex, snapshot(JSON), version, checksum, createdAt }
```

**设计原因**：双写 Redis + PG —— Redis 求快（恢复常在短时间内发生），
PG 求稳（审计与故障恢复）。带 version/checksum 防止"用错快照"导致数据错乱。

---

## 7. 人工审批流程（单级）

```
[Agent] 危险动作 → [Gate] 命中 → [Engine] PENDING → [通知] → [审批人]
                                                                 │
                                     ┌───────────────────────────┤
                                     ▼            ▼               ▼
                                 APPROVE       REJECT          MODIFY
                                     │            │               │
                                  RESUME       ABORT        改参数→RETRY
                                     │            │               │
                                     └──── 审计日志 AuditLog ───────┘
```

---

## 8.企业审批流程（多级会签）

```
金额 / 风险等级 决定审批链：

 request(amount=120万)
      │
      ▼
 L1 组长审批 ── APPROVE ──►  L2 总监审批 ── APPROVE ──► L3 财务VP审批 ── APPROVE ──► FINAL_APPROVED
      │                        │                          │
    REJECT                   REJECT                     REJECT
      │                        │                          │
      └───────── 任一级驳回 → 整体 REJECTED（并记录驳回级别与理由）────────────────┘

审批链由 ApprovalPolicy 动态计算：
  amount < 1万      → [L1]
  1万 ≤ amount <10万 → [L1, L2]
  amount ≥ 10万    → [L1, L2, L3]
```

**设计原因**：审批链**数据驱动**（策略可配置），而非硬编码 if-else，
便于不同企业/不同业务线复用同一引擎。

---

## 9. Feedback 与 Feedback Learning

```
每次人类决策 → FeedbackRecord{ requestId, decision, reason, operator, features }
      │
      ▼
FeedbackEngine.record()  ── 落库
      │
      ├─ 统计：某类动作历史通过率
      ▼
FeedbackLearner.learn()
      │  若"同类动作连续 N 次被批准且风险低" → 生成 AutoApprovePolicy
      ▼
下次同类动作 → Gate 直接放行（或降级为"事后通知"）→ 降低审批成本
```

---

## 10. 模块关系总表

| 模块 | 依赖 | 被谁调用 | 职责 |
|------|------|----------|------|
| humancore | — | 所有模块 | 领域模型、状态机、枚举 |
| approvalengine | humancore, checkpoint | Gate/API | 审批状态流转、多级链 |
| interruptmanager | humancore, checkpoint | Gate | 挂起任务、登记待办 |
| resumeengine | humancore, checkpoint | approvalengine | 恢复执行 |
| checkpointmanager | humancore | interrupt/resume | 快照存取（Redis+PG） |
| feedbackengine | humancore | approvalengine | 记录决策、反馈学习 |
| approvalapi | approvalengine | 前端/IM | REST 接口 |
| approvalui | approvalapi | 浏览器 | 审批中心页面 |

---

## 11. 为什么这样设计 / 如何扩展

- **横切、可插拔**：ApprovalGate 用统一接口包裹动作，前十天任何执行点都能"一行接入"。
- **状态机中心化**：所有状态流转唯一收口在 `ApprovalStateMachine`，杜绝散落的 if。
- **存储分层**：Redis(快/锁) + PG(稳/审计)，可按需替换为 Kafka/消息队列驱动异步审批。
- **策略数据化**：RiskPolicy / ApprovalPolicy / AutoApprovePolicy 都可外部配置，支持多租户。
- **面向 Day12**：挂起不占线程 + Checkpoint 续跑，天然支撑 Long Running Agent。

---

*架构讲解到此。代码将随各 chapter 逐步落地，每章末暂停等待确认。*