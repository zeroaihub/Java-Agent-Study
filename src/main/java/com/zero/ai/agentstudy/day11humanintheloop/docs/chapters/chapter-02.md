# Chapter 02 · 领域建模与审批状态机

> 本章目标：为整个 HITL 模块打好「地基」——把审批世界里的每一个概念（动作、风险、请求、决策、状态、流转）都用最恰当的 Java 类型表达出来，并用一台**中心化的状态机**统一管理所有状态流转，从此杜绝散落在业务代码里的 `if/else` 意大利面。
>
> 本章代码位于：`day11humanintheloop/humancore/`
> - `model/`：领域模型（RiskLevel、ApprovalStatus、ApprovalTransition、AgentAction、ApprovalDecision、ApprovalRequest）
> - `statemachine/`：状态机（ApprovalStateMachine、IllegalTransitionException）
> - `spi/`：风险策略扩展点（RiskPolicy、DefaultRiskPolicy）

---

## 一、为什么要学：没有领域模型和状态机，HITL 会烂成什么样？

在 Chapter 01 我们讲清了 HITL 的「战略意义」——让人在关键环节介入 Agent 的决策。但战略落地到代码，第一步永远是**建模**。很多人跳过这一步，直接上来写 `ApprovalService`，结果三个月后代码变成这样：

```java
// 反面教材：真实项目里腐化的审批代码
public void handleApproval(String id, String action, ...) {
    Order order = orderMapper.selectById(id);
    if ("approve".equals(action)) {
        if (order.getStatus() == 1) {          // 1 是啥？PENDING 吗？
            if (order.getLevel() < order.getMaxLevel()) {
                order.setStatus(1);            // 又回到 1？
                order.setLevel(order.getLevel() + 1);
            } else {
                order.setStatus(2);            // 2 又是啥？
            }
        } else if (order.getStatus() == 3) {   // 3 能 approve 吗？没人知道
            // ... 谁也不敢删这个分支
        }
    } else if ("reject".equals(action)) {
        // 又是一大坨嵌套 if
    }
    // ...此处省略 200 行，且每个人都往里加自己的分支
}
```

这段代码的问题，本质上是**三个建模缺失**：

1. **状态没有类型**：用 `int`（1/2/3）表示状态，语义全靠注释和口口相传，改一个数字就能引发线上事故。
2. **流转规则没有收口**：「什么状态能做什么操作」这条业务规则，被拆散到无数个 `if` 里，没有任何一个地方能让你一眼看全。
3. **非法操作静默通过**：一个已经被驳回的请求又被 approve，代码不报错、不拦截，默默改了数据——这是审批系统最致命的 bug。

**领域建模 + 状态机**就是这三个问题的标准解药：
- 用 `enum` 给状态一个有名字、有语义、编译期可校验的类型；
- 用一张「转移表」把所有流转规则收敛到一个类里；
- 用「非法流转抛异常」把「不该发生的事」变成显式失败。

> 一句话总结学它的理由：**建模决定了系统的上限，状态机决定了系统的下限。** 模型建对了，后面所有章节（审批引擎、中断恢复、多级会签）都是水到渠成；模型建错了，后面每一章都在还债。

---

## 二、是什么：领域模型与状态机的核心概念

### 2.1 领域驱动设计（DDD）的三种建模元素

我们这一章用到了 DDD 里最基础也最实用的三种建模元素，请务必分清它们的区别：

| 元素 | 特征 | 本章实例 | Java 表达 |
| --- | --- | --- | --- |
| **值对象（Value Object）** | 不可变、无唯一标识、由属性定义相等性 | `AgentAction`、`ApprovalDecision` | `record` |
| **实体（Entity）** | 有唯一标识、有生命周期、状态会变 | `ApprovalRequest` | `class` + `final id` |
| **枚举 / 值集合** | 有限、封闭的取值集合 | `RiskLevel`、`ApprovalStatus`、`ApprovalTransition` | `enum` |

**为什么动作要用值对象（record）？** 因为一个「Agent 想删除订单 12345」这件事一旦成立，它就不该再变。如果它是可变的，那么审批人看到的是「删除订单 12345」，点了同意之后，代码执行前有人偷偷把它改成「删除所有订单」——灾难。不可变性是审批安全的基石。

**为什么审批请求要用实体（class）？** 因为它有生命周期：它会从 PENDING 走到 APPROVED，走到 FINAL_APPROVED，中途还会累积决策历史。它有唯一 `requestId`，即使两个请求内容一模一样，它们也是两个不同的请求。

### 2.2 状态机（State Machine）

状态机是计算机科学里最古老、最可靠的模型之一。它由三要素构成：

- **状态（State / 节点）**：系统在某一刻的「所处位置」。本章即 `ApprovalStatus`。
- **流转（Transition / 边）**：从一个状态到另一个状态的「动作」。本章即 `ApprovalTransition`。
- **转移函数（Transition Function）**：`(当前状态, 动作) → 下一个状态`。本章即 `ApprovalStateMachine.next()`。

用图来表达我们的审批状态机（核心视图）：

```
                    ┌──────────────────────────────────────────────┐
                    │                                                │
                    ▼                                                │ NEXT_LEVEL（还有下一级）
   ┌─────────┐  APPROVE   ┌──────────┐                              │
   │ PENDING ├───────────►│ APPROVED ├──────────────────────────────┘
   │(待审批) │            │(某级通过)│
   └────┬────┘            └────┬─────┘
        │                      │ FINALIZE（最后一级）
        │ MODIFY               ▼
        ▼                 ┌────────────────┐
   ┌──────────┐           │ FINAL_APPROVED │  ← 终态（可执行）
   │ MODIFIED │           └────────────────┘
   │(人工改过)│
   └────┬─────┘
        │ RESUBMIT（改完重新提交）
        └────────────────────► 回到 PENDING

   任意非终态 ── REJECT ──►  REJECTED   ← 终态
   任意非终态 ── TIMEOUT ─►  TIMEOUT    ← 终态
   任意非终态 ── ABORT ───►  ABORTED    ← 终态
```

**转移表（本章代码的真正核心，`ApprovalStateMachine.buildTable()`）：**

| 当前状态 \ 动作 | APPROVE | NEXT_LEVEL | FINALIZE | REJECT | MODIFY | RESUBMIT | TIMEOUT | ABORT |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **PENDING** | APPROVED | — | — | REJECTED | MODIFIED | — | TIMEOUT | ABORTED |
| **APPROVED** | — | PENDING | FINAL_APPROVED | — | — | — | — | ABORTED |
| **MODIFIED** | — | — | — | REJECTED | — | PENDING | TIMEOUT | ABORTED |
| **FINAL_APPROVED**（终态） | ✗ 全部非法 | | | | | | | |
| **REJECTED**（终态） | ✗ 全部非法 | | | | | | | |
| **TIMEOUT**（终态） | ✗ 全部非法 | | | | | | | |
| **ABORTED**（终态） | ✗ 全部非法 | | | | | | | |

表格里的每一个「—」和「✗」都是一条业务规则。表格空着的格子 = 非法流转 = 抛 `IllegalTransitionException`。这就是状态机的威力：**规则可视化、可穷举、可测试。**

### 2.3 SPI（Service Provider Interface）：风险策略扩展点

`RiskPolicy` 是本章唯一的「接口」。它回答一个问题：**「这个动作有多危险？」** 我们把它做成 SPI 而非硬编码，是因为「风险」的定义因企业而异——这一点会在第四节详细展开。

---

## 三、怎么用：完整代码逐行精讲

本节把 `humancore` 下的每个类都讲透。请对照源码阅读。

### 3.1 RiskLevel —— 风险等级枚举

```java
public enum RiskLevel {
    NONE, LOW, HIGH;
    public boolean requiresApproval() {
        return this != NONE;
    }
}
```

- 只有三档，刻意保持简单：**NONE**（放行）、**LOW**（可单级审批）、**HIGH**（需多级会签）。
- `requiresApproval()` 把「是否需要审批」这个判断封装进枚举本身，而不是让调用方写 `if (level == LOW || level == HIGH)`——这叫「充血枚举」，让枚举拥有行为，而不只是数据。

### 3.2 ApprovalStatus —— 审批状态（状态机的节点）

```java
public enum ApprovalStatus {
    PENDING(false), APPROVED(false), FINAL_APPROVED(true),
    REJECTED(true), MODIFIED(false), TIMEOUT(true), ABORTED(true);

    private final boolean terminal;
    ApprovalStatus(boolean terminal) { this.terminal = terminal; }
    public boolean isTerminal() { return terminal; }
}
```

关键设计：**每个状态自带 `terminal` 属性**，标记它是否为「终态」。终态意味着流程结束、不可再流转。把这个属性放进枚举，状态机就能用 `status.isTerminal()` 一行判断，而不用维护一个额外的「终态集合」。

- 非终态：`PENDING`（等人批）、`APPROVED`（某一级通过、等下一级或终审）、`MODIFIED`（人工改过、等重新提交）。
- 终态：`FINAL_APPROVED`（终审通过、可执行）、`REJECTED`（驳回）、`TIMEOUT`（超时）、`ABORTED`（终止）。

### 3.3 ApprovalTransition —— 流转动作（状态机的边）

把「动作」也建模成枚举，是状态机能用「一张表」表达的前提。8 个动作对应转移表的 8 列，详见源码 `ApprovalTransition.java`。特别注意三个「多级会签专用」动作：`NEXT_LEVEL`（进入下一级）、`FINALIZE`（终审通过）、`RESUBMIT`（改完重新提交）——它们让单级和多级审批共用同一台状态机。

### 3.4 AgentAction —— Agent 动作（不可变值对象）

核心看它的**紧凑构造器**：

```java
public AgentAction {
    Objects.requireNonNull(taskId, "taskId 不能为空");
    Objects.requireNonNull(type, "type 不能为空");
    Objects.requireNonNull(description, "description 不能为空");
    params = (params == null) ? Map.of() : Map.copyOf(params);   // 防御性拷贝
}
```

两个工程细节：
1. **非空校验前置**：不合法的动作根本无法被创建，把错误挡在最前面（fail-fast）。
2. **防御性拷贝 `Map.copyOf`**：即使外部持有传进来的那个 map 引用并偷偷修改，本对象内部的 `params` 也纹丝不动。这是「不可变」的真正落地——不是加个 `final` 就完事了。

### 3.5 ApprovalDecision —— 审批决策（不可变事件）

它记录「谁、在何时、做了什么决定、附带什么意见/修改」。提供了 `approve` / `reject` / `modify` 三个静态工厂方法，让调用处语义清晰：`ApprovalDecision.reject("张三", "金额超预算")`。它是**审计链**的最小单元——每一次决策都是一条不可篡改的历史记录。

### 3.6 ApprovalRequest —— 审批请求（聚合根 / 实体）

这是本章唯一的「有状态实体」，也是最需要仔细设计的类。它的字段分两类：

**不可变字段（`final`）**：`requestId`、`action`、`riskLevel`、`requiredLevels`、`createdAt`、`expireAt`——这些创建后永不改变。

**可变字段（受控）**：`status`（当前状态）、`approvedLevels`（已通过级数）、`decisions`（决策历史）。

关键设计原则：**可变字段绝不暴露 setter，只提供「受状态机调用」的受控方法**：

```java
// 由状态机调用：应用一个新状态（不做合法性校验，合法性由状态机保证）
public void applyStatus(ApprovalStatus newStatus) {
    this.status = Objects.requireNonNull(newStatus);
}

// 记录决策 + 推进已通过级数（只增不改，形成审计链）
public void recordDecision(ApprovalDecision decision) {
    this.decisions.add(Objects.requireNonNull(decision));
    if (decision.isApproval()) {
        this.approvedLevels++;
    }
}
```

为什么不叫 `setStatus` 而叫 `applyStatus`？命名即约定——`applyStatus` 传递的语义是「这是状态机算好之后让你落地的，不是随便谁都能调的 setter」。同时 `getDecisions()` 返回 `Collections.unmodifiableList`，杜绝外部往历史里插数据或删数据。

两个便捷工厂：`ApprovalRequest.single(...)`（单级审批）、`ApprovalRequest.multiLevel(..., levels)`（多级会签），都默认 24 小时超时。

### 3.7 ApprovalStateMachine —— 状态机核心（本章的大脑）

它有三个层次的 API，从纯查询到实际驱动：

**① 纯查询（无副作用）**：

```java
boolean canFire(ApprovalStatus from, ApprovalTransition t);      // 能不能做
Set<ApprovalTransition> allowedTransitions(ApprovalStatus from); // 能做哪些（给前端渲染按钮用）
```

**② 纯计算（无副作用，非法则抛异常）**：

```java
public ApprovalStatus next(ApprovalStatus from, ApprovalTransition transition) {
    Map<ApprovalTransition, ApprovalStatus> row = table.getOrDefault(from, Map.of());
    ApprovalStatus to = row.get(transition);
    if (to == null) {
        throw new IllegalTransitionException(from, transition);  // ← 非法流转显式失败
    }
    return to;
}
```

**③ 实际驱动（有副作用，就地更新请求）**：`fire(request, decision)`。这是唯一改变请求状态的入口。它的执行顺序极其讲究：

```java
public ApprovalStatus fire(ApprovalRequest request, ApprovalDecision decision) {
    ApprovalStatus from = request.getStatus();
    ApprovalTransition transition = decision.transition();

    ApprovalStatus to = next(from, transition);   // ① 先校验合法性（非法直接抛，什么都没改）
    request.recordDecision(decision);              // ② 先记审计（保证异常前历史已落地）

    if (transition == ApprovalTransition.APPROVE) {// ③ 多级会签自动判断
        request.applyStatus(ApprovalStatus.APPROVED);
        if (request.allLevelsApproved()) {
            request.applyStatus(next(ApprovalStatus.APPROVED, ApprovalTransition.FINALIZE));
        } else {
            request.applyStatus(next(ApprovalStatus.APPROVED, ApprovalTransition.NEXT_LEVEL));
        }
        return request.getStatus();
    }

    request.applyStatus(to);                       // ④ 其它动作直接应用
    return request.getStatus();
}
```

**这段代码为什么这么写，是本章精华：**
- **先校验再改**：`next()` 放在最前面，一旦非法就抛异常，此时请求「一个字段都没动」，保证原子性。
- **APPROVE 的自动会签**：审批人只需点「同意」，至于「这是最后一级（终审通过）还是中间级（进入下一级）」由状态机根据 `allLevelsApproved()` 自动决定。审批人不需要知道自己是第几级，大幅降低前端复杂度。

### 3.8 转移表用 EnumMap 的性能考量

`table` 用的是 `EnumMap` 而非 `HashMap`：EnumMap 底层就是一个数组，用 enum 的 `ordinal()` 做下标，查表是 O(1) 且几乎零哈希开销、零装箱，内存也更紧凑。状态机是每次审批操作都要查的热路径，这个选择在高并发下有实际收益。

### 3.9 端到端演示：一个删单动作如何走完全流程

```java
// 1) Agent 想删订单
AgentAction action = AgentAction.of("task-001", "ORDER_DELETE", "删除测试订单 12345");

// 2) 风险策略评估 → HIGH
RiskPolicy policy = new DefaultRiskPolicy();
RiskLevel level = policy.evaluate(action);           // HIGH
boolean needApproval = level.requiresApproval();     // true

// 3) 创建两级会签请求
ApprovalRequest req = ApprovalRequest.multiLevel(action, level, 2);
// req.getStatus() == PENDING

// 4) 一级主管同意
ApprovalStateMachine sm = new ApprovalStateMachine();
sm.fire(req, ApprovalDecision.approve("主管-李四", "确认是测试数据"));
// approvedLevels=1 < 2 → 状态回到 PENDING，等二级

// 5) 二级经理同意
sm.fire(req, ApprovalDecision.approve("经理-王五", "同意删除"));
// approvedLevels=2 == 2 → FINAL_APPROVED（可执行！）

// 6) 若此时有人再想操作 → 抛 IllegalTransitionException
// sm.fire(req, ApprovalDecision.reject("路人", "..."));  // ✗ 终态不允许
```

---

## 四、用在哪：真实企业项目中的落地场景

### 4.1 电商 ERP —— 批量删除测试订单（本课程最终目标）

这正是 Day11 要跑通的场景。运营想清理测试订单，Agent 生成 `ORDER_DELETE` 动作 → `DefaultRiskPolicy` 判定 HIGH（因为 `ORDER_DELETE` 在高危集合里）→ 触发两级会签 → 主管 + 经理都同意后才真正执行删除。整个流程的状态流转，全部由本章的 `ApprovalStateMachine` 驱动。

### 4.2 金融风控 —— 大额转账审批

银行的转账 Agent，`RiskPolicy` 换成「按金额分档」的实现：转账 < 1 万 → LOW（柜员单批）；≥ 1 万 → HIGH（柜员 + 主管 + 风控三级会签）。注意：**状态机一行代码都不用改**，只需换 `RiskPolicy` 实现，把 `requiredLevels` 设为 3。这就是 SPI + 中心化状态机的威力——业务规则变了，内核稳如泰山。

### 4.3 内容平台 —— 群发通知/推送审批

Agent 想给百万用户群发 push，`RiskPolicy` 判定「群发类动作」为 HIGH，进入审批。审批人可以用 `MODIFY` 动作修改推送文案（`ApprovalDecision.modify(...)`），请求进入 `MODIFIED` 状态，改完 `RESUBMIT` 重新走审批——这条「修改-重提交」的支线，正是转移表里 MODIFIED 行存在的意义。

### 4.4 运维 —— 生产环境高危命令审批

运维 Agent 想执行 `DROP TABLE` / `kubectl delete`，`RiskPolicy` 判 HIGH，且设置 `expireAt` 为 30 分钟（用 `isExpired()` 判超时）。超过 30 分钟无人审批 → 定时任务扫描到 → `fire(req, TIMEOUT decision)` → 进入 TIMEOUT 终态，动作自动放弃，避免高危操作「挂着挂着被误点」。

---

## 五、避坑指南（≥10 条）+ 小结 + FAQ + 面试题

### 5.1 十二条避坑清单

1. **别用 int/String 表示状态**。用 `enum`。魔法数字（status==2）是审批系统事故的头号来源，编译器帮不了你。
2. **别把流转规则散落在各个 Service**。所有「什么状态能做什么」必须收口到 `ApprovalStateMachine` 一个地方，新增规则 = 改一行转移表。
3. **非法流转必须抛异常，绝不能静默放过**。「已驳回的请求又被同意」如果不报错，就是在数据库里悄悄制造脏数据，事后无法追责。
4. **值对象必须真不可变**。`record` 只保证引用不变，`Map`/`List` 字段一定要 `Map.copyOf` / `List.copyOf` 做防御性拷贝，否则「不可变」是假的。
5. **实体的可变字段绝不暴露裸 setter**。只开放「受状态机调用」的受控方法（如 `applyStatus`），并在命名上暗示其约束。
6. **决策历史只增不改**。`getDecisions()` 返回 `unmodifiableList`，审计链一旦被允许删改，合规性荡然无存。
7. **先校验再改状态，保证原子性**。`fire()` 里 `next()` 放最前面，非法时请求一个字段都没动，不会出现「改了一半」的中间态。
8. **先记审计再改状态**。`recordDecision` 在 `applyStatus` 之前，确保即使后续抛异常，「谁做过什么」的记录也已落地。
9. **多级会签的「第几级」不要让审批人感知**。审批人只点「同意」，是否终审由 `allLevelsApproved()` 自动判断，降低前端和人的心智负担。
10. **终态要显式建模并校验**。用 `ApprovalStatus.terminal` 属性标记，转移表里终态行留空，任何对终态的操作自动非法。
11. **超时不要靠「查询时判断」，要靠「主动流转」**。`isExpired()` 只是判定，真正要有定时任务把超时请求 `fire(TIMEOUT)` 推进到终态，否则会有大量「僵尸 PENDING」堆积。
12. **RiskPolicy 绝不能返回 null**。契约上无风险返回 `RiskLevel.NONE`，返回 null 会让下游 `requiresApproval()` 直接 NPE。`DefaultRiskPolicy` 的每条分支都有明确返回值。

### 5.2 本章小结

本章我们用「值对象 + 实体 + 枚举 + 状态机 + SPI」五件套，为 HITL 打好了地基：
- `RiskLevel` / `ApprovalStatus` / `ApprovalTransition` 用枚举给风险、状态、动作以类型和语义；
- `AgentAction` / `ApprovalDecision` 用不可变 record 保证安全与可审计；
- `ApprovalRequest` 用受控实体聚合一次审批的全部信息；
- `ApprovalStateMachine` 用一张转移表中心化管理所有流转，非法即抛异常；
- `RiskPolicy` 用 SPI 把「风险定义」交给接入方，内核对扩展开放、对修改关闭。

有了这层地基，下一章（Chapter 03）我们将在其之上构建**审批引擎（ApprovalEngine）与审批网关（ApprovalGate）**，把这些模型真正「跑」起来，接上存储、接上并发控制。

### 5.3 FAQ

**Q1：为什么 APPROVED 不是终态？** 因为 APPROVED 只代表「某一级通过了」。单级审批下它会立刻 FINALIZE 到 FINAL_APPROVED；多级会签下它可能 NEXT_LEVEL 回到 PENDING。真正「可执行」的终态是 FINAL_APPROVED。

**Q2：MODIFIED 状态有什么用？** 它支持「审批人不是简单同意/驳回，而是改了参数再让流程重走」。比如推送文案不合适，审批人改了文案（MODIFY→MODIFIED），改完重新提交（RESUBMIT→PENDING）。

**Q3：状态机是单例吗？线程安全吗？** 是。`ApprovalStateMachine` 无可变实例字段（转移表构造后只读），完全线程安全，可作为 Spring 单例注入。它「计算」下一状态，真正的状态存在于各自的 `ApprovalRequest` 实体里。

**Q4：为什么不用现成的状态机框架（如 Spring StateMachine）？** 教学阶段我们手写一个轻量状态机，是为了让你彻底理解原理；生产中当状态、事件、守卫条件极其复杂时，可平滑替换为 Spring StateMachine，但接口（fire/next）设计思想完全一致。

### 5.4 面试题

1. 请解释值对象（VO）和实体（Entity）的区别，并说明为什么审批动作用 record、审批请求用 class。
2. 什么是状态机？用「转移表」实现状态机相比「散落的 if/else」有哪些优势？
3. `Map.copyOf` 在不可变对象里起什么作用？只加 `final` 够不够，为什么？
4. 多级会签场景下，审批人点「同意」后，状态机如何自动区分「进入下一级」还是「终审通过」？
5. 为什么 `fire()` 要「先校验合法性、再记审计、最后改状态」？调换顺序会有什么问题？
6. 为什么转移表用 `EnumMap` 而不是 `HashMap`？
7. RiskPolicy 为什么设计成 SPI 接口而不是写死的类？请举一个「换实现不改内核」的例子。

---

> **本章到此结束。请回复「继续」，进入 Chapter 03：审批引擎与审批网关（ApprovalEngine + ApprovalGate）。**