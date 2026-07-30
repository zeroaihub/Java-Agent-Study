# Chapter 04 · Interrupt / Resume —— Agent 执行的「挂起」与「恢复」

> 本章目标：实现 HITL 最核心的运行时能力——让 Agent 在危险动作前**停下来（Interrupt）**，等待人类决策后再**接着跑（Resume）**。我们将建模执行现场、中断信号、中断管理器与恢复引擎，把「暂停-继续」这条闭环打通。

---

## 一、为什么要学：Agent 不能是「一条道跑到黑」

### 1.1 一个真实的翻车现场

设想一个运维 Agent 的自动化脚本，它一口气把整个流程跑完：

```java
// 反面教材：没有任何中断点，一条道跑到黑
public void runCleanup() {
    List<Order> orders = queryOrders();     // step 0: 查出 12 万条订单
    backup(orders);                         // step 1: 备份
    deleteAll(orders);                      // step 2: 删除（危险！但没人拦得住）
    sendReport();                           // step 3: 发报告
}
```

这段代码的致命问题：**step 2 删除 12 万条数据这种高危操作，没有任何机会让人介入**。一旦 `runCleanup()` 被触发，Agent 就会义无反顾地删到底。等人发现不对，数据已经没了。

真实事故往往就是这么发生的：Agent 判断失误（比如把生产库当成测试库），而系统缺少「停下来问一句」的能力。

### 1.2 我们需要什么能力

HITL 要求 Agent 的执行必须是**可打断、可恢复**的：

- **Interrupt（中断）**：执行到危险动作前，能停下来，把「现在跑到哪、已经做了什么」保存下来；
- **Resume（恢复）**：人类批准后，能从中断点**接着跑**，而不是从头重来；
- **Modify + Retry（修改后重跑）**：人类改了参数后，能从指定步重新执行；
- **Reject（驳回）**：人类不同意，执行安全放弃，不留半吊子状态。

这就是本章要构建的四个能力，对应四个类：`ExecutionContext`（执行现场）、`InterruptSignal`（中断信号）、`InterruptManager`（中断管理器）、`ResumeEngine`（恢复引擎）。

---

## 二、是什么：把「执行过程」变成有状态、可控制的对象

### 2.1 核心洞察：中断/恢复的前提是「执行有状态」

普通方法调用是「无状态」的——方法一旦进入就跑到 return，中间没有可观测、可干预的点。要实现中断/恢复，必须把执行过程本身建模成一个**有状态的对象**：它知道自己「跑到第几步」「处于什么状态」「有哪些中间结果」。

这个对象就是 `ExecutionContext`。有了它，中断就是「把状态改成挂起 + 保存现场」，恢复就是「把状态改回运行 + 定位断点」。

### 2.2 执行状态机（Execution State）

```
        ┌─────────┐  interrupt(审批)   ┌──────────────────┐
        │ RUNNING │ ─────────────────▶ │ WAITING_APPROVAL │
        │         │  interrupt(其它)   ├──────────────────┤
        │         │ ─────────────────▶ │   INTERRUPTED    │
        └────┬────┘                    └────────┬─────────┘
             │                                  │ resume / resumeFrom
             │ complete                         ▼
             ▼                            ┌──────────┐  (瞬时)
        ┌───────────┐                     │ RESUMED  │──────┐
        │ COMPLETED │                     └──────────┘      │ 回到
        └───────────┘                                       ▼ RUNNING
             ▲ abort / reject                          （继续执行）
        ┌────┴────┐
        │ ABORTED │
        └─────────┘
```

- `RUNNING`：正常执行中；
- `INTERRUPTED` / `WAITING_APPROVAL`：两种挂起态（前者泛指人工干预/等输入，后者专指等审批）；
- `RESUMED`：瞬时态，标记「刚被恢复」，随即回到 RUNNING；
- `COMPLETED` / `ABORTED`：终态。

### 2.3 本章交付的类

| 类 | 角色 | 位置 |
|----|------|------|
| `ExecutionState` | 执行状态枚举 | `interruptmanager/` |
| `InterruptReason` | 中断原因枚举 | `interruptmanager/` |
| `ExecutionContext` | 执行现场（实体） | `interruptmanager/` |
| `InterruptSignal` | 中断信号（值对象） | `interruptmanager/` |
| `InterruptManager` | 中断管理器 | `interruptmanager/` |
| `ResumeEngine` | 恢复引擎 | `resumeengine/` |

---

## 三、怎么用：逐类精讲

### 3.1 `ExecutionState` / `InterruptReason`——两个枚举

`ExecutionState` 用 `terminal` 标记终态、用 `isSuspended()` 判断是否挂起——把「状态语义」内聚在枚举里（充血枚举），调用方不用到处写 `== INTERRUPTED || == WAITING_APPROVAL`。

`InterruptReason` 区分了四种触发源，`isHumanTriggered()` 帮助后续策略判断「是人主动停的还是系统/规则停的」——这对恢复策略和审计都有意义。

### 3.2 `ExecutionContext`——执行现场

```java
public class ExecutionContext {
    private final String executionId;   // 一次执行的唯一标识
    private final String taskId;        // 归属任务
    private volatile ExecutionState state;
    private volatile int currentStep;   // 当前跑到第几步
    private volatile int resumeStep;    // 恢复时从第几步继续
    private final ConcurrentHashMap<String, Object> variables; // 中间结果
}
```

**为什么字段是 `volatile`？** 因为执行线程和中断线程可能是不同线程：执行线程在跑 `advanceStep()`，中断线程在调 `transitTo()`。`volatile` 保证一个线程的修改对另一个线程立即可见，避免中断线程读到过期的状态。

**`currentStep` 与 `resumeStep` 为什么分开？**
- `currentStep`：客观事实，「实际跑到了第几步」；
- `resumeStep`：恢复策略，「恢复时应该从第几步继续」。

正常恢复时二者相等；但当人类**修改任务后想重跑某几步**时，可以把 `resumeStep` 往回调（比如 `currentStep=5` 但 `resumeStep=3`，表示从第 3 步重来）。分开建模，才能同时支持 Continue 和 Retry。

**`snapshotVariables()` 用 `Map.copyOf`** 返回只读快照，为 Chapter 05 的 Checkpoint 冻结现场做准备——检查点需要的是某一刻的不可变副本，而非活着的引用。

### 3.3 `InterruptSignal`——中断信号（不可变凭证）

```java
public record InterruptSignal(
        String executionId, InterruptReason reason, int atStep,
        AgentAction triggerAction, String message, Instant signaledAt) { ... }
```

用 `record` 保证不可变：中断信号一旦产生就是一条审计记录，绝不能被后续修改。两个静态工厂 `forApproval` / `forHumanPause` 覆盖最常见的两种场景，减少调用方样板代码。

### 3.4 `InterruptManager`——中断管理器

**（1）注册执行现场**

```java
public ExecutionContext register(String executionId, String taskId) {
    ExecutionContext ctx = new ExecutionContext(executionId, taskId);
    contexts.put(executionId, ctx);
    return ctx;
}
```

Agent 开始执行时先注册，管理器用 `ConcurrentHashMap` 持有所有活跃执行现场。

**（2）发起中断——核心方法**

```java
public ExecutionContext interrupt(InterruptSignal signal) {
    ExecutionContext ctx = require(signal.executionId());
    if (ctx.getState().isTerminal()) {                    // ① 终态不可中断
        throw new IllegalStateException("执行已处于终态，无法中断：...");
    }
    ExecutionState target = (signal.reason() == InterruptReason.APPROVAL_REQUIRED)
            ? ExecutionState.WAITING_APPROVAL             // ② 审批触发 → 等审批
            : ExecutionState.INTERRUPTED;                 //    其它 → 通用挂起
    ctx.transitTo(target);
    signals.computeIfAbsent(...).add(signal);             // ③ 记录信号（审计）
    return ctx;
}
```

三个要点：
- **终态守卫**：已经 COMPLETED/ABORTED 的执行不能再被中断，否则会产生非法状态；
- **按原因选目标状态**：审批触发进 `WAITING_APPROVAL`，其它进 `INTERRUPTED`——为后续差异化恢复策略铺路；
- **先改状态再记信号**：信号列表 `computeIfAbsent` 保证并发下也能安全地为每个执行初始化信号列表。

**（3）终止与查询**

`abort()` 把执行切到 ABORTED（幂�等：已终态直接返回）；`isSuspended()` 供恢复引擎校验；`signalsOf()` 返回不可变的信号历史用于审计。

### 3.5 `ResumeEngine`——恢复引擎

恢复引擎依赖 `InterruptManager`（它才是执行现场的持有者），提供三种恢复语义：

**（1）`resume`——原地继续（Continue）**

```java
public ExecutionContext resume(String executionId) {
    ExecutionContext ctx = requireSuspended(executionId);  // 必须处于挂起态
    ctx.transitTo(ExecutionState.RESUMED);                 // 瞬时态：便于监控看到「刚恢复」
    ctx.transitTo(ExecutionState.RUNNING);                 // 回到运行
    return ctx;
}
```

**为什么要经过 `RESUMED` 这个瞬时态？** 纯粹为了可观测性：监控/审计系统可以捕捉到「这个执行是从挂起被恢复的，而不是一直在跑」。业务上它立刻回到 RUNNING。

**（2）`resumeFrom`——从指定步重跑（Modify Task + Retry）**

```java
public ExecutionContext resumeFrom(String executionId, int fromStep) {
    ExecutionContext ctx = requireSuspended(executionId);
    ctx.setResumeStep(fromStep);          // 关键：把恢复断点调到 fromStep
    ctx.transitTo(ExecutionState.RESUMED);
    ctx.transitTo(ExecutionState.RUNNING);
    return ctx;
}
```

人类修改了动作参数后，往往希望「从改动生效的那一步」重新开始，而不是从头也不是从中断点。`resumeFrom` 就是干这个的。

**（3）`reject` / `complete`——驳回与完成**

`reject` 委托 `interruptManager.abort()` 让执行进 ABORTED；`complete` 把跑完所有步骤的执行标记 COMPLETED（终态幂等）。

**关键设计：恢复不是「代码指针跳转」。** 引擎只负责「状态复位 + 断点定位」，真正「从第 N 步接着跑」是上层执行器读 `resumeStep` 后自己驱动的。引擎与执行器职责分离，引擎才能保持纯粹、可测。

---

## 四、用在哪：真实项目中的 Interrupt / Resume

### 4.1 四类典型场景

| 场景 | 中断原因 | 挂起态 | 恢复方式 |
|------|----------|--------|----------|
| ERP 批量删单，删除前需主管审批 | `APPROVAL_REQUIRED` | `WAITING_APPROVAL` | 批准 → `resume`；拒绝 → `reject` |
| 运维脚本执行到高危命令（drop table） | `SYSTEM_GUARD` | `INTERRUPTED` | 确认无误 → `resume` |
| 金融交易金额超限，等待风控人工核验 | `APPROVAL_REQUIRED` | `WAITING_APPROVAL` | 核验通过 → `resume` |
| Agent 缺少必要信息，需人类补充输入 | `WAITING_INPUT` | `INTERRUPTED` | 补充后改参数 → `resumeFrom` |

这四类覆盖了 HITL 里「危险动作拦截」「等外部决策」「等外部输入」三大需求。

### 4.2 端到端演示：ERP 删单的挂起与恢复

```java
public class InterruptResumeDemo {
    public static void main(String[] args) {
        InterruptManager interruptManager = new InterruptManager();
        ResumeEngine resumeEngine = new ResumeEngine(interruptManager);

        // === 1. Agent 开始执行，注册执行现场 ===
        String execId = "exec-20260727-001";
        ExecutionContext ctx = interruptManager.register(execId, "task-erp-delete");
        System.out.println("初始状态: " + ctx.getState());        // RUNNING

        // === 2. 执行前几步：查询、备份 ===
        ctx.putVariable("orderCount", 5);
        ctx.advanceStep();  // step 0 → 1: 查询完成
        ctx.advanceStep();  // step 1 → 2: 备份完成
        System.out.println("当前步骤: " + ctx.getCurrentStep());   // 2

        // === 3. 跑到危险步「删除」前，触发中断等待审批 ===
        AgentAction deleteAction = AgentAction.of(
                "task-erp-delete", "DELETE", "删除 5 条测试订单",
                Map.of("count", 5, "env", "test"));
        InterruptSignal signal = InterruptSignal.forApproval(
                execId, ctx.getCurrentStep(), deleteAction, "删除操作需主管审批");
        interruptManager.interrupt(signal);
        System.out.println("中断后状态: " + ctx.getState());       // WAITING_APPROVAL
        System.out.println("是否挂起: " + interruptManager.isSuspended(execId)); // true

        // === 4a. 场景一：主管批准 → 原地继续 ===
        resumeEngine.resume(execId);
        System.out.println("恢复后状态: " + ctx.getState());       // RUNNING
        ctx.advanceStep();  // step 2 → 3: 执行删除
        resumeEngine.complete(execId);
        System.out.println("最终状态: " + ctx.getState());         // COMPLETED

        // === 4b. 场景二(另一次执行)：主管要求改参数后重跑 ===
        // resumeEngine.resumeFrom(execId, 1);  // 从第 1 步（备份）重来

        // === 4c. 场景三(另一次执行)：主管驳回 ===
        // resumeEngine.reject(execId, "生产环境禁止批量删除");
        //  → 状态变 ABORTED

        // === 5. 审计：回看这次执行的所有中断信号 ===
        interruptManager.signalsOf(execId)
                .forEach(s -> System.out.println("审计: " + s.reason() + " @step" + s.atStep()));
    }
}
```

### 4.3 挂起-恢复时序图

```
执行器          InterruptManager      ResumeEngine        人类
  │  register(execId)   │                  │               │
  │───────────────────▶│                  │               │
  │  advanceStep x2     │                  │               │
  │─(内部)              │                  │               │
  │  到危险步,interrupt  │                  │               │
  │───────────────────▶│ transitTo         │               │
  │                     │ WAITING_APPROVAL  │               │
  │                     │ 记录InterruptSignal│              │
  │                     │                  │  提交审批请求   │
  │                     │                  │──────────────▶│
  │                     │                  │               │ 审阅
  │                     │                  │◀──────批准─────│
  │                     │  resume(execId)  │               │
  │                     │◀─────────────────│               │
  │                     │ RESUMED→RUNNING   │             │
  │  读 resumeStep 续跑  │                  │               │
  │◀────────────────────│                  │               │
  │  complete(execId)   │                  │               │
  │───────────────────▶│ COMPLETED         │               │
```

---

## 五、避坑指南 + 小结 + FAQ + 面试题

### 5.1 避坑指南（≥12 条）

1. **状态字段必须 `volatile`**：执行线程和中断线程往往不是同一个。不加 `volatile`，中断线程可能读到过期状态，导致「以为在 RUNNING 其实已经 COMPLETED」的错乱。

2. **中断前必须做终态守卫**：`interrupt()` 里若不判断 `isTerminal()`，就可能把一个已经 COMPLETED 的执行拉回挂起态，产生僵尸执行。

3. **恢复前必须校验挂起态**：`resume` 只应作用于 INTERRUPTED/WAITING_APPROVAL。对 RUNNING 或终态执行调 resume 是逻辑错误，必须抛异常（`requireSuspended`）而非静默放行。

4. **`resumeStep` 越界要防**：如果上层传入的 `fromStep` 大于 `currentStep` 或为负，等于让执行「跳到没跑过的步」或「倒回不存在的步」，必须校验区间。

5. **并发中断竞态**：两个线程可能同时对同一 execId 调 `interrupt`。用 `ConcurrentHashMap` + 状态机的原子转移收敛竞态，避免信号丢失或状态覆盖。

6. **中断信号绝不可丢**：`InterruptSignal` 是审计凭证，用 `record` + `CopyOnWriteArrayList` 保证不可变、不丢失。事故复盘时「谁在第几步、因为什么停了」全靠它。

7. **恢复不是「代码指针跳转」**：本章引擎只改状态、定断点，真正续跑由执行器读 `resumeStep` 驱动。别指望 `resume()` 会自动帮你「跳回第 N 行代码」——那是执行器的活。

8. **执行现场要及时清理**：`ExecutionContext` 常驻 `ConcurrentHashMap`，执行终态后若不 `remove`，会内存泄漏。生产中应在 COMPLETED/ABORTED 后清理或转持久化（Chapter 05）。

9. **`RESUMED` 是瞬时态，别当稳定态用**：它只为可观测性存在，紧接着就回 RUNNING。不要有业务逻辑长期依赖「停在 RESUMED」。

10. **`snapshotVariables()` 必须返回只读副本**：用 `Map.copyOf`。若直接返回内部 Map 引用，调用方一改就污染了执行现场，Checkpoint 也会跟着被篡改。

11. **中断原因要区分人触发 vs 系统触发**：`isHumanTriggered()` 影响恢复策略与责任归属。系统守卫触发的中断和人主动暂停，审计口径和后续处理是不一样的。

12. **`interrupt` 与 `advanceStep` 的时序**：应在「执行危险步之前」中断，而不是执行到一半。中断点要卡在动作边界上，否则会出现「删了一半被停下」的脏状态。

13. **幂等性**：`abort`/`complete` 对已终态执行应幂等返回，不抛异常——重复的完成/终止信号在分布式环境很常见。

### 5.2 小结

本章把「执行过程」从无状态的方法调用，升级成了**有状态、可打断、可恢复**的对象。核心是四件事：

- `ExecutionContext` 承载执行现场（状态 + 步骤 + 中间结果），用 `volatile` 保证跨线程可见；
- `InterruptSignal` 作为不可变审计凭证记录「何时、何步、因何」中断；
- `InterruptManager` 集中管理执行现场与信号，负责发起中断（含终态守卫、按原因分流）；
- `ResumeEngine` 提供 Continue / Retry / Reject 三种恢复语义，只做状态复位与断点定位。

至此，「暂停-继续」的闭环已经打通。但有一个隐患：执行现场目前只活在内存里——一旦进程重启，所有挂起中的执行就全丢了。这正是下一章 **Chapter 05 · Checkpoint Manager** 要解决的：把 `ExecutionContext` 快照并持久化，让执行能跨进程、跨重启恢复。

### 5.3 FAQ

**Q1：中断/恢复和线程的 `wait()/notify()`、`Thread.interrupt()` 有什么区别？**
A：JDK 的线程中断是「线程级」的信号，粒度粗、无法携带业务语义、更无法持久化。我们做的是「业务级」的中断：它有明确的状态机、可审计的信号、可恢复的断点，且不绑定具体线程模型——将来可以是线程、协程，甚至是跨进程的分布式执行。

**Q2：为什么不直接把执行状态存数据库，而要先在内存里建 `ExecutionContext`？**
A：内存对象是运行时的「工作副本」，读写快、适合高频的 `advanceStep`。持久化是 Chapter 05 的职责，通过 Checkpoint 定期/关键点快照。运行时用内存、持久化用检查点，是性能与可靠性的平衡。

**Q3：`WAITING_APPROVAL` 和 `INTERRUPTED` 为什么要分成两个状态,不能合并吗？**
A：可以合并，但会丢信息。分开后，监控面板能一眼区分「有多少执行卡在等审批」和「有多少被系统守卫拦下」，恢复策略也能差异化（等审批的要挂审批流，系统守卫的可能人工确认即可）。语义清晰的代价只是多一个枚举值,值得。

**Q4：如果人类一直不审批，挂起的执行会一直挂着吗？**
A：本章还没处理超时——它属于 Chapter 06 多级审批的超时机制。当前设计已为此预留：`InterruptSignal` 带 `signaledAt` 时间戳，配合定时扫描即可实现「超时自动 abort 或升级」。

**Q5：`resumeFrom` 从第 3 步重跑，那第 4、5 步已经产生的中间结果怎么办？**
A：这取决于业务语义。本章的 `ExecutionContext` 只负责调整 `resumeStep`；至于要不要清理 `variables` 里第 4、5 步的产物，由上层执行器决定。引擎不替业务做「脏数据清理」的假设，保持通用。

### 5.4 面试题

1. **请设计一个「可中断、可恢复」的任务执行模型，说明核心要素。**（考点：执行现场对象化、状态机、断点、信号）

2. **为什么 `ExecutionContext` 的状态字段要用 `volatile`？什么情况下 `volatile` 不够、需要用锁或原子类?**（考点：JMM 可见性 vs 原子性；复合操作需 CAS/锁）

3. **`currentStep` 和 `resumeStep` 分开建模的意义是什么?举一个只有分开才能实现的场景。**（考点:Continue vs Retry;修改参数后从指定步重跑）

4. **中断管理器在并发场景下如何保证「多个线程同时中断同一执行」不出错?**（考点:ConcurrentHashMap、状态机原子转移、computeIfAbsent）

5. **恢复引擎经过一个瞬时的 `RESUMED` 态再回 `RUNNING`,这样设计的收益和代价分别是什么?**（考点:可观测性 vs 状态数增加;瞬时态的取舍）

6. **如果要让挂起中的执行在进程重启后仍能恢复,你会怎么改造现有设计?**（考点:引出 Checkpoint 持久化;快照时机;反序列化恢复）

7. **中断信号为什么用不可变的 `record`?如果允许修改会有什么风险?**（考点:审计凭证不可篡改;并发安全;事故复盘可信度）

8. **「恢复不是代码指针跳转」这句话怎么理解?引擎和执行器的职责边界在哪?**（考点:引擎只做状态复位+断点定位;续跑由执行器驱动;可测性与解耦）