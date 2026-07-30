# Chapter 01 · HITL 总论：为什么 Agent 必须停下来问人

> 本章是 Day11 的地基。目标：让你从"工程与商业"双视角，彻底想清楚
> **"Agent 的自动化边界在哪里"**，并建立起 HITL 的整体心智模型。
> 本章不写业务代码，但会给出核心接口的骨架，为后续章节铺路。

---

## 第一部分 · 为什么学

### 1.1 一个真实会让你赔钱的场景

想象 ZeroHub 上线了一个"运维 Agent"，用户说：

> "把测试环境里过期的订单清理一下。"

Planning Agent 生成计划 → Browser/DB Agent 执行 → 一条 SQL：

```sql
DELETE FROM orders WHERE env = 'test';
```

看起来没问题。但如果：
- 模型把 `env` 字段名记错，实际库里叫 `environment`，`env` 恒为 NULL，条件失效；
- 或者测试数据和生产数据**共用一张表**，只靠 `env` 区分，而某批生产数据 `env` 被误标为 test；

结果就是：**生产订单被删光**。而这条命令，Agent 一秒钟就执行完了，没有任何人看过。

这不是"模型不够聪明"能解决的问题——**再聪明的概率系统也会有小概率犯致命错误**。
工程上唯一可靠的答案是：**在不可逆动作前，插入一个人类确认点。**

### 1.2 为什么 AI Agent 不能完全自动

| 缺陷 | 表现 | 为什么 HITL 能兜底 |
|------|------|-------------------|
| 幻觉 | 编造字段/订单号/API | 人一眼看出"这订单号不对" |
| 语义歧义 | "测试订单"定义不清 | 人来界定边界 |
| Prompt 注入 | 被诱导执行恶意指令 | 人不会批准明显异常的动作 |
| 无责任主体 | 出事没人负责 | 审批人签字=责任落地 |

### 1.3 为什么企业必须保留人工审批

- **合规要求**：金融、医疗、政务，法规明文要求关键操作留痕、可追责。
- **责任归属**：企业需要"谁批的"这条审计链，而模型无法承担法律责任。
- **风险不对称**：自动化省下的是"几秒人力"，一旦出错赔的是"几百万 + 品牌"。收益与风险严重不对称的动作，必须人来把关。

### 1.4 头部产品的共识

- **Cursor / Copilot Workspace**：AI 生成 diff，但**默认不自动提交**，等你 Accept。
  → 因为"改代码"是可逆的（可撤销），但"提交/推送"影响团队，风险升级，需要人确认。
- **Devin**：跑到关键节点**主动 pause 等确认**。
- **Manus**：高风险动作**暂停请求介入**。
- **OpenAI Operator**：**支付/下单前弹确认框**。
- **飞书/钉钉审批、Jira Workflow、ServiceNow**：企业流程**本身就是审批状态机**。

**它们的统一范式**：
> **AI 负责"把方案做到可执行"，人类保留"要不要执行"的最终开关。**

### 1.5 商业价值

1. **可卖给企业**：没有审批与审计，AI Agent 进不了金融/政企客户的采购清单。
2. **降低事故成本**：一次误删的赔偿，足以覆盖 HITL 模块的全部开发成本。
3. **建立信任**：用户敢把权限给你，是因为知道"关键时刻能喊停"。
4. **可渐进自动化**：先全人工审，再靠 Feedback Learning 逐步放开低风险动作，
   实现"信任随数据增长而增长"的商业闭环。

---

## 第二部分 · 是什么

### 2.1 HITL 的本质定义

> **Human-in-the-loop = 在 Agent 的自动执行回路中，插入一个"人类决策节点"，
> 使某些动作在执行前/执行中必须获得人类授权。**

它由三件事构成：

1. **拦截（Gate）**：判断"这个动作要不要人？"
2. **挂起 + 快照（Interrupt + Checkpoint）**：停下来，并保存现场，保证能恢复。
3. **决策 + 恢复（Approval + Resume）**：人做决定，系统据此继续/终止/修改重跑。

### 2.2 心智模型：把"自动流"变成"可控流"

```
自动流（危险）：
   感知 → 规划 → 执行 → 收尾      （全自动，一气呵成，出错无法挽回）

可控流（HITL）：
   感知 → 规划 → [Gate?] → 执行 → 收尾
                    │命中
                    ▼
             挂起 + 快照
                    │
                人类决策
             ┌──────┼──────┬───────┐
          批准    驳回    修改    超时
             │      │      │       │
           恢复    终止  改后重跑  兜底
```

### 2.3 核心概念全景（本模块 13 个词）

```
                     ┌──────────────── Human-in-the-loop ────────────────┐
                     │                                                   │
     ┌───────────────┴───────────────┐                ┌──────────────────┴─────────────┐
     │           拦截 & 挂起           │                │           决策 & 恢复            │
     │  RiskPolicy → ApprovalGate     │                │  Approval / Reject / Modify     │
     │  Interrupt / Pause             │                │  Retry / Continue / Resume      │
     │  Checkpoint（快照）             │                │  Multi-Level Approval           │
     └────────────────────────────────┘                │  Human Feedback / Learning      │
                     │                                  │  Audit Log                      │
                     └──────────── State Machine 统一编排 ┘
```

- **Interrupt vs Pause**：Interrupt 通常由"风险命中"触发、要走审批；Pause 更轻，
  可能只是用户手动暂停，不一定需要审批。二者都由状态机统一表达。
- **Reject vs Continue**：Reject 是"否决方案"；Continue 是"暂停后继续"。
- **Retry vs Modify Task**：Retry 是"原样重跑"；Modify Task 是"改了参数/Prompt 再跑"。
- **Checkpoint vs Memory（Day04）**：Memory 存"长期知识"；Checkpoint 存"某次执行的瞬时现场"，
  用完即可清理，带 version/checksum，强调**可恢复性**而非**可记忆性**。

### 2.4 底层如何实现（Java 视角）

HITL 的工程内核可以抽象为**三个接口 + 一台状态机**：

```java
// 1) 风险判定：这个动作要不要人？
public interface RiskPolicy {
    RiskLevel evaluate(AgentAction action);   // NONE / LOW / HIGH
}

// 2) 审批网关：统一入口，包裹任意动作
public interface ApprovalGate {
    // 命中风险 → 挂起并返回"待审"；否则直接放行执行
    GateResult guard(AgentAction action, ExecutionContext ctx);
}

// 3) 审批引擎：管理审批请求的生命周期
public interface ApprovalEngine {
    ApprovalRequest create(AgentAction action, ExecutionContext ctx);
    void approve(String requestId, String operator, String reason);
    void reject(String requestId, String operator, String reason);
    void modify(String requestId, String operator, Map<String,Object> newParams);
}
```

而"合法状态流转"由一台状态机守卫（下一章实现）：

```
PENDING ──approve──► APPROVED
PENDING ──reject───► REJECTED
PENDING ──modify───► MODIFIED
PENDING ──timeout──► TIMEOUT
（其余流转一律非法，直接抛异常）
```

### 2.5 Spring AI / Spring Boot 如何集成

- 用 **Spring Bean** 承载 `RiskPolicy` / `ApprovalGate` / `ApprovalEngine`，天然可注入、可替换。
- 用 **状态字段 + 存储**（Redis/PG）表达挂起，而**不是阻塞线程**——
  这是与"同步等待用户输入"最大的区别，也是能支撑 Day12 长任务的关键。
- 审批通知、UI、API 都是**外围适配器**，内核不依赖它们（六边形架构思想）。

### 2.6 为什么这样设计

- **单一入口（Gate）**：把"要不要人"的判断收敛到一处，接入成本极低（包一层即可）。
- **状态机中心化**：所有流转唯一收口，杜绝散落各处的 `if (status == ...)`。
- **挂起不阻塞**：用状态而非线程表达等待，扛得住海量并发长任务。
- **内核与适配器分离**：换 UI、换 IM、换存储都不动核心逻辑。

---

## 第三部分 · 怎么用（本章：搭建可编译的接口骨架）

> 本章只落"契约层"（接口 + 枚举 + 值对象），保证能编译；具体实现从 chapter-02 开始。
> 目标包：`com.zero.ai.agentstudy.day11humanintheloop.humancore`

### 3.1 先定义"动作"和"风险等级"

```java
package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

/** Agent 想要执行的一个动作（要被审批网关拦截的对象） */
public record AgentAction(
        String taskId,          // 所属任务
        String type,            // 动作类型：DB_DELETE / HTTP_CALL / BROWSER_CLICK ...
        String description,     // 人类可读描述："批量删除测试订单"
        java.util.Map<String, Object> params,  // 动作参数（可被人工修改）
        Long amount             // 涉及金额（元），无则为 null，用于多级审批
) {}
```

```java
package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

/** 风险等级：决定是否需要人、需要几级人 */
public enum RiskLevel {
    NONE,   // 无需审批，直接放行
    LOW,    // 低风险，单级审批或自动放行
    HIGH    // 高风险，必须审批（可能多级）
}
```

### 3.2 审批状态枚举（状态机的"节点"）

```java
package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

public enum ApprovalStatus {
    PENDING,          // 等待审批
    APPROVED,         // 已批准（单级终态 / 多级中间态）
    FINAL_APPROVED,   // 多级全部通过
    REJECTED,         // 已驳回
    MODIFIED,         // 人工修改后待重跑
    TIMEOUT,          // 超时
    ABORTED           // 终止
}
```

### 3.3 核心接口（契约先行）

```java
package com.zero.ai.agentstudy.day11humanintheloop.humancore.spi;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.RiskLevel;

/** 风险策略：判断一个动作的风险等级 */
public interface RiskPolicy {
    RiskLevel evaluate(AgentAction action);
}
```

**为什么用 `record` + `enum` + `interface`**：
- `record`：值对象天然不可变，适合快照与传递，减少并发 bug。
- `enum`：状态/等级是有限集合，用枚举让状态机校验一目了然。
- `interface`：契约先行，实现可替换（内存版 → Redis 版 → 分布式版）。

**替代方案与取舍**：
- 也可以用 BPMN 引擎（Activiti/Flowable）做审批流——功能强但重，学习/运维成本高；
  我们自研轻量状态机，**更贴合 Agent 场景、可控、易嵌入**，企业里两条路线都常见。

> 以上代码将在 chapter-02 正式落盘并补全实现。本章先建立契约与心智。

---

## 第四部分 · 真实项目（HITL 用在哪）

| 领域 | 何处需要人 | 何时可自动 |
|------|-----------|-----------|
| AI Coding（Cursor/Copilot） | 提交、推送、发布 | 生成代码、本地编辑 |
| AI Office | 对外发邮件、删除文件 |起草、整理、内部检索 |
| AI 客服 | 退款、赔付、封号 | 答疑、查单、话术推荐 |
| AI Trading | 下单、调仓、转账 | 行情分析、回测、生成策略 |
| AI 知识库 | 删除/覆盖知识 | 检索、问答、摘要 |
| 合同/财务审批 | 签署、付款（多级会签） | 起草、比对、风险提示 |
| Browser Agent | 删除、支付、提交表单 | 浏览、抓取、填充草稿 |

**判断"要不要人"的黄金准则**：
> **可逆 + 低影响 → 自动；不可逆 或 高影响 或 对外 或 花钱 → 人工。**

**如何降低审批成本**（企业最关心）：
1. 只在"高风险动作"设卡，不要一步一问（否则 Agent 变"人工助手"）。
2. 用 **Feedback Learning** 把重复批准的低风险动作转为自动放行。
3. 批量审批、卡片式一键审批、IM 内审批，减少上下文切换。

---

## 第五部分 · 避坑与优化（≥10 条）

1. **审批死锁**：无人处理导致任务永久挂起 → 必须有**超时兜底**（TIMEOUT + 默认策略）。
2. **重复审批 / 重复执行**：审批人多次点击、恢复被触发两次 → **幂等 + 分布式锁**（requestId 去重）。
3. **超时策略缺失**：超时后既不拒也不升级 → 明确"超时=拒绝"还是"超时=升级上级"。
4. **审批丢失**：只存内存，重启即丢 → 关键状态**持久化到 PG**，Redis 仅作加速。
5. **Checkpoint 不一致**：先执行后快照 → 顺序错了无法恢复 → 铁律**先快照、后执行**。
6. **恢复用错快照**：多次挂起产生多份快照 → 用 **version/checksum + taskId+stepIndex** 精确定位。
7. **人工修改导致数据错误**：随意改参数 → 修改必须**再次校验 + 走一遍风险判定**，不可无脑重跑。
8. **重复恢复**：Resume 被并发调用 → 恢复入口**加锁 + 状态判定**（仅 APPROVED 可恢复一次）。
9. **日志缺失**：出了事查不到谁批的 → **全链路 AuditLog**，决策/操作人/时间/理由全留痕。
10. **权限失控**：谁都能批百万转账 → **RBAC**，按角色/金额上限限制审批权。
11. **审批性能瓶颈**：同步阻塞线程等人 → **异步挂起**，用状态表达等待，不占线程。
12. **通知不到位**：审批人不知道有待办 → **多通道通知**（IM/邮件/Webhook）+ 待办中心。

### 本章小结

- HITL 的本质：**在自动回路里插入人类决策节点**，用"确定性闸门"约束"概率系统"。
- 判断准则：**不可逆/高影响/对外/花钱 → 人工**。
- 工程内核：**RiskPolicy（判风险）+ ApprovalGate（拦截）+ 状态机（守流转）+ Checkpoint（保恢复）**。
- 核心心法：**先快照、后执行；异步挂起、不占线程；一切决策落审计。**

### 常见问题（FAQ）

- **Q：每步都问人不是很烦？** A：只在高风险设卡 + Feedback Learning 逐步放开，体验与安全兼顾。
- **Q：挂起会不会把线程耗尽？** A：用状态而非阻塞表达等待，所以不会。
- **Q：Checkpoint 和 Memory么区别？** A：Checkpoint 是"某次执行的现场快照"，Memory 是"长期知识"。

### 面试题

1. 为什么 LLM 不能完全自动执行不可逆动作？从工程与合规两方面回答。
2. HITL 中"挂起"为什么不能用阻塞线程实现？会有什么后果？
3. 为什么必须"先 Checkpoint 后执行"？顺序反了会怎样？
4. 如何设计审批的幂等与超时兜底？
5.单级审批与多级会签在状态机上的差异是什么？

### 扩展阅读

- LangGraph：`interrupt()` 与 `Command(resume=...)` 的人机协同模型。
- Temporal：Human Task / Signal 机制。
- AWS Step Functions：`.waitForTaskToken` 回调式等待。
- BPMN：User Task 与会签（Multi-Instance）。

---

*本章讲授完毕。请回复"继续"，进入 chapter-02：领域建模与审批状态机（开始落盘可运行代码）。*