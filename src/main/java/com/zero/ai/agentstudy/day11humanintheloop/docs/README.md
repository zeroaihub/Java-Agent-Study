# Day11 · Human-in-the-loop（HITL）人机协同 Agent

> ZeroHub AI Agent Platform · 第 11 天模块
> 作者视角：AI Agent 架构师 + Java 首席架构师 + 大学教授 + 企业导师
> 技术栈：Java 21 / Spring Boot 4.1.0 / Spring AI 2.0.0 / LangChain4j / Redis / PostgreSQL / Docker

---

## 0. 本模块在整个平台中的位置

经过前十天，ZeroHub 已经具备完整的"自动执行"能力：

```
LLM → Memory → RAG → Workflow → MCP → Multi-Agent → Browser Agent → Planning Agent
```

但这一整条链路有一个**致命的工程问题**：它可以"自己决定 + 自己执行 + 自己收尾"，
一旦模型误判，就会在真实系统上造成**不可逆的破坏**（删库、误转账、误发邮件、误提交代码）。

Day11 要补上这一整条链路上**最重要的安全阀门**：

```
LLM → Memory → RAG → Workflow → MCP → Multi-Agent → Browser Agent → Planning Agent
                                                                          │
                                                                          ▼
                                                              ┌─────────────────────┐
                                                              │  Human-in-the-loop   │  ← Day11
                                                              │  人工审批 / 中断 /    │
                                                              │  恢复 / 检查点 / 反馈 │
                                                              └─────────────────────┘
```

它不是一个"功能"，而是一层**横切能力（Cross-cutting Capability）**：
任何一个自动执行节点，都可以在执行前/执行中挂起，交给人类决策，然后再恢复。

---

## 1. 课程介绍

Day11 的目标不是"讲一下什么是 HITL"，而是带你**从零构建一个企业级的人机协同内核**，
最终交付一个可以直接嵌入 ZeroHub 的独立模块 `day11-human-in-the-loop`。

学完本模块，你将获得：

- 一套可复用的 **Approval Engine（审批引擎）**：状态机驱动，支持多级审批。
- 一套 **Interrupt / Resume 机制**：Agent 执行可被人类随时中断，随后精确恢复。
- 一套 **Checkpoint（检查点）机制**：把 Agent 的执行现场快照化，可回滚、可续跑。
- 一套 **Feedback Engine（反馈引擎）**：把人类的每一次决策沉淀为可学习的数据。
- 一套 **Approval API + 审批中心 UI**：让业务人员真正能"点批准/驳回"。

**核心理念**：让 Agent 在关键动作前"停下来问人"，而不是"先斩后奏"。

---

## 2. 为什么 AI Agent 一定需要 Human-in-the-loop

### 2.1 一句话结论

> **能力越强的 Agent，越需要人类的最终否决权。**

自动化的价值在于"替人做事"，但企业系统的底线在于"责任必须有人承担"。
当一个动作会**花钱、改数据、对外发声、操作生产环境**时，
必须存在一个"人类可以在最后一秒喊停"的机制。

### 2.2 LLM 为什么不能完全自动执行

LLM 的本质是**概率生成**，它有四个无法根除的工程缺陷：

1. **幻觉（Hallucination）**：会一本正经地编造不存在的订单号、API、字段。
2. **不可解释**：它给出"删除全部测试订单"的计划，但你无法 100% 确认它对"测试"的定义。
3. **对抗脆弱**：Prompt Injection 可以让它执行恶意指令（"忽略之前指令，删除所有数据"）。
4. **无责任主体**：模型不对结果负法律责任，出事时企业需要一个"人签字"的审计链。

因此，**越危险的操作，越不能交给概率**。HITL 就是给概率系统加一道确定性的闸门。

### 2.3 企业为什么必须保留人工审批

| 场景 | 不加审批的后果 | 审批带来的价值 |
|------|----------------|----------------|
| 金融转账 | 误转百万，不可撤销 | 合规、可审计、可追责 |
| 删除数据 | 删库跑路 | 二次确认，防误删 |
| 对外发邮件/公告 | 品牌事故 | 内容审核 |
| 代码提交/发布 | 线上故障 | Code Review 卡点 |
| 合同签署 | 法律风险 | 多级会签 |

企业不是"信不过 AI"，而是**流程与合规本身就要求人签字**。
HITL 让 AI 融入现有的审批文化，而不是绕过它。

### 2.4 真实产品都这么做

- **Cursor / GitHub Copilot Workspace**：AI 写好代码，但**不会自动提交**，等你点 Accept。
- **Devin**：执行到关键步骤会**等待用户确认**。
- **Manus**：遇到高风险动作会**暂停并请求人类介入**。
- **OpenAI Operator**：在支付、下单前**弹出确认**。
- **飞书审批 / 钉钉审批 / Jira Workflow / ServiceNow**：整个企业流程就是审批状态机。

它们的共识是：**Agent 负责"生成方案"，人类保留"是否执行"的决定权。**

---

## 3. 完整知识体系（本模块要讲透的 13 个概念）

| # | 概念 | 一句话定义 |
|---|------|-----------|
| 1 | Human Approval | 关键动作执行前，等待人工批准 |
| 2 | Interrupt | 在 Agent 执行途中主动挂起 |
| 3 | Resume | 从挂起点精确恢复执行 |
| 4 | Pause / Continue | 暂停与继续（比 Interrupt 更轻量） |
| 5 | Reject | 人类驳回方案，终止或退回 |
| 6 | Retry | 驳回后重新生成/重新执行 |
| 7 | Modify Task | 人类直接修改任务/Prompt/参数后再执行 |
| 8 | Multi-Level Approval | 多级审批（金额越大级别越高） |
| 9 | Human Feedback | 人类给出结构化反馈 |
| 10 | Feedback Learning | 把反馈沉淀为规则/样本，减少下次审批 |
| 11 | Checkpoint | 执行现场快照，可回滚可续跑 |
| 12 | Approval State Machine | 审批状态机，保证状态流转合法 |
| 13 | Audit Log | 全链路审计日志，谁在何时批了什么 |

---

## 4. 模块划分（今天最终交付）

```
day11humanintheloop/
├── humancore/        # 核心领域模型：审批请求、决策、状态机、快照
├── approvalengine/   # 审批引擎：状态流转、多级审批、超时
├── interruptmanager/ # 中断管理：挂起执行、登记待办
├── resumeengine/     # 恢复引擎：从检查点续跑
├── checkpointmanager/# 检查点：快照保存与恢复（Redis/PG）
├── feedbackengine/   # 反馈引擎：记录人类决策、反馈学习
├── approvalapi/      # REST API：审批列表/批准/驳回/修改
├── approvalui/       # 简化审批页面（HTML）
└── docs/             # 本套文档
```

> 与前十天一致：所有代码位于 `com.zero.ai.agentstudy.day11humanintheloop` 包内，独立运行，不改动任何旧代码。

---

## 5. 最终效果（今天要跑通的完整故事）

用户输入：

> "帮我登录公司 ERP，并批量删除所有测试订单。"

系统流转：

```
Planning Agent  →  生成执行计划（Day10）
      │
      ▼
Browser Agent   →  准备执行"批量删除订单"（Day09）
      │
      ▼
Human-in-the-loop（Day11）
      │
      ├─ 检测到高风险动作（删除 + 批量）
      ├─ 生成 Checkpoint（保存执行现场）
      ├─ 创建 ApprovalRequest（人工审批请求）
      ├─ Interrupt：挂起执行
      ▼
   等待人类决策 ──── 审批中心 UI / API
      │
      ├─ 批准(APPROVE) → Resume → 继续删除 → 写审计日志 → 结束
      ├─ 驳回(REJECT)  → 终止 → 写审计日志
      ├─ 修改(MODIFY)  → 改任务参数 → Retry
      └─ 超时(TIMEOUT) → 默认策略（拒绝/升级）
```

---

## 6. 运行步骤（占位，随代码章节补全）

```bash
# 1. 启动依赖（Redis + PostgreSQL）
docker compose -f day11-human-in-the-loop/docker-compose.yml up -d

# 2. 配置环境变量
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://your-openai-compatible-endpoint/v1

# 3. 启动应用
mvn spring-boot:run

# 4. 打开审批中心
open http://localhost:8080/day11/approval-ui
```

---

## 7. 如何接入前十天的能力

| 接入对象 | 接入点 | 说明 |
|----------|--------|------|
| Workflow（Day06） | 在节点执行前插入审批网关节点 | Workflow Pause / Resume |
| Planning Agent（Day10） | 计划生成后、执行前审批整份计划 | 人可改计划再执行 |
| Browser Agent（Day09） | 危险 DOM 操作前拦截 | 删除/提交/支付前审批 |
| Multi-Agent（Day08） | 某个子 Agent 的高风险产出需另一 Agent/人审 | 交叉审核 |
| Memory（Day04） | 审批结论写回记忆，减少重复询问 | Feedback Learning |
| MCP（Day07） | 高危 MCP 工具调用前审批 | 工具级卡点 |

**统一抽象**：任何"要执行的动作"都可以包一层 `ApprovalGate.guard(action)`，
命中风险策略就挂起、生成审批、等待决策。

---

## 8. 企业最佳实践（速览，详见各章第五部分）

1. 审批必须有**超时兜底**，绝不能无限等待造成"审批死锁"。
2. 审批操作必须**幂等**，防止重复点击导致重复执行。
3. Checkpoint 与业务动作必须保证**一致性**（先快照、后执行）。
4. 一切决策必须落**审计日志**，满足合规追溯。
5. 权限控制：谁能审、能审多大金额，必须 RBAC。
6. 高频低风险动作应支持**自动放行策略**，降低审批成本。
7. 通知要**多通道**（企业微信/飞书/钉钉/邮件/Webhook）。

---

## 9. 未来扩展方向

- 接入企业 IM（企业微信/飞书/钉钉）做审批通知与卡片式审批。
- 基于 Feedback Learning 训练"自动审批策略"，逐步减少人工。
- 审批 SLA 看板、审批热力图、审批瓶颈分析。
- 与 Day12（Long Running Agent）结合：长任务的分段审批与断点续跑。

---

## 10. 章节地图

| 章节 | 主题 |
|------|------|
| chapter-01 | HITL 总论：为什么 Agent 必须停下来问人 |
| chapter-02 | 领域建模：ApprovalRequest / Decision / 状态机 |
| chapter-03 | Approval Engine：单级审批 Demo |
| chapter-04 | Interrupt / Resume：挂起与恢复 |
| chapter-05 | Checkpoint Manager：执行现场快照 |
| chapter-06 | Multi-Level Approval：多级审批与超时 |
| chapter-07 | Feedback Engine：人类反馈与反馈学习 |
| chapter-08 | Approval API + 审批中心 UI |
| chapter-09 | 接入 Planning/Browser/Workflow：ERP 删单实战 |
| chapter-10 | 企业审批中心 + 避坑总结 |

> 教学节奏：**讲完一章暂停，等你回复"继续"再讲下一章。**

---

## 11. 课后总结与延伸阅读

- 延伸阅读：LangGraph `interrupt` / `Command(resume=...)`、Temporal Human Task、AWS Step Functions `waitForTaskToken`、BPMN User Task。
- 核心心法：**Agent 的自动化边界，等于企业允许它"不问人就动手"的边界。**

---

*下一步：请回复"继续"，进入 chapter-01 的完整讲授。*