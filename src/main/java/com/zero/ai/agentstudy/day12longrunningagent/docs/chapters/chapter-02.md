# Chapter 02 · Agent Runtime 与 Lifecycle 状态机

> 本章目标：讲清 Agent Runtime 的职责边界，以及生命周期状态机（Lifecycle State Machine）的设计与 Java 实现。本章给出 `AgentState`、`AgentStateMachine`、`IllegalStateTransitionException` 三个可运行、可测试的生产级代码，是后续所有章节的地基。

---

## 第一部分 · 为什么学

### 2.1 为什么 Agent 需要"运行时（Runtime）"？

在 Chapter 01 我们建立了"业务层 + 运行时层"的两层心智模型。运行时层要解决的第一个、也是最根本的问题是：**如何描述并约束一个长任务在其生命周期中的所有可能状态？**

想象没有运行时的世界：你在业务代码里用一个 `String status` 字段记录状态，某处写 `status = "running"`，另一处写 `status = "done"`。随着代码膨胀，很快就会出现：

- 拼写不一致（`"running"` vs `"Running"` vs `"RUNNING"`）。
- 非法流转（一个已经 `"done"` 的任务被某个漏网的分支重新置为 `"running"`，导致重复执行、重复发消息、重复扣款）。
- 状态含义漂移（`"waiting"` 到底是"等审批"还是"等定时"？没人说得清）。

这些问题在 ChatBot 里不致命（反正无状态），但在长任务里是**灾难级**的——因为长任务有真实副作用，一次错误的重复执行可能就是一笔错误的资金流水。

**Runtime 的第一职责，就是把"状态"从一个随意的字符串，升级为一个受状态机严格约束的、语义明确的、不可非法流转的一等公民。**

### 2.2 为什么必须用"状态机"而不是"if-else"？

有人会说：我用 if-else 判断状态流转不行吗？不行，原因有三：

1. **规则分散**：if-else 散落在几十个方法里，没有任何地方能一眼看全"到底哪些流转是合法的"。状态机把规则**集中**在一处（一张流转表），可读、可审计、可测试。
2. **易漏易错**：新增一个状态时，if-else 需要在所有相关分支补充判断，极易遗漏。状态机只需在流转表里加一行。
3. **无法形式化验证**：状态机是数学上被充分研究的模型（有限状态自动机），可以做可达性分析、死锁检测。散乱的 if-else 无法。

这就是为什么 Temporal、Netflix Conductor、Spring StateMachine、AWS Step Functions，无一例外都以"显式状态机"为核心。

### 2.3 Runtime 的完整职责清单

Runtime 是总控，它的职责在后续章节逐步实现，这里先建立全景：

| 职责 | 说明 | 章节 |
| --- | --- | --- |
| 生命周期管理 | 定义状态、约束流转（本章） | Ch02 |
| Session 管理 | 创建/查询长任务实例 | Ch03 |
| State 持久化 | 把上下文落库 | Ch03 |
| 执行引擎 | 逐步执行 Step | Ch08 |
| Checkpoint/Recovery | 打点与崩溃恢复 | Ch04 |
| 调度/队列/重试 | 定时、缓冲、失败处理 | Ch05 |
| 事件驱动 | 广播生命周期事件 | Ch06 |
| 监控/日志 | 可观测性 | Ch07 |

本章聚焦第一项：生命周期与状态机。

---

## 第二部分 · 是什么

### 2.4 生命周期状态定义

我们定义 8 个状态，覆盖长任务的全部生命周期语义：

```
CREATED    已创建，未启动
RUNNING    运行中（正在执行某步）
SUSPENDED  已挂起（等审批 / 等回调），可 resume
RETRYING   某步失败，重试等待中
WAITING    周期任务本轮完成，等下一次定时触发
COMPLETED  成功结束（终态）
FAILED     失败结束（终态，通常进 DLQ）
CANCELLED  被取消（终态）
```

其中 `COMPLETED / FAILED / CANCELLED` 是**终态**，无任何出边；其余是**活跃态**，崩溃后需要被 Recovery 扫描恢复。

### 2.5 状态机流转图

```
        create
          │
          ▼
      ┌────────┐  start   ┌─────────┐  next step (自环)
      │CREATED │─────────►│ RUNNING │──────────┐
      └───┬────┘          └────┬────┘◄─────────┘
          │ cancel             │
          ▼                    ├── suspend ──►┌───────────┐ resume ┌─────────┐
      ┌──────────┐             │              │ SUSPENDED │───────►│ RUNNING │
      │CANCELLED │◄────────────┤              └─────┬─────┘        └─────────┘
      └──────────┘  cancel     │                    │ cancel/fail
                               │                    ▼
          ┌────────────────────┤              ┌───────────┐
          │ error(retryable)   │all done     │ FAILED    │──► DLQ
          ▼                    ▼              └───────────┘
      ┌───────────┐  retry     ┌───────────┐
      │ RETRYING  │──►RUNNING  │ COMPLETED │ (终态)
      └─────┬─────┘            └───────────┘
            │ retry>max
            ▼
      ┌───────────┐  cron next fire   ┌─────────┐
      │  FAILED   │   WAITING ───────►│ RUNNING │
      └───────────┘                   └─────────┘
```

### 2.6 底层实现原理

状态机的底层实现只有一个核心数据结构：**一张"源状态 → 允许的目标状态集合"的映射表**（转移函数 δ）。判断流转是否合法，就是查这张表是否包含目标状态。这是有限状态自动机（DFA）的标准实现。

在 Java 中，我们用 `EnumMap<AgentState, EnumSet<AgentState>>` 实现这张表。选择 `EnumMap`/`EnumSet` 的原因：它们针对枚举做了极致优化（底层是位向量/数组，O(1) 查询、内存紧凑），是枚举场景的最佳容器。

---

## 第三部分 · 怎么用（完整可运行 Java 代码）

### 2.7 AgentState 枚举

文件：`day12longrunningagent/lifecycle/AgentState.java`

```java
package com.zero.ai.agentstudy.day12longrunningagent.lifecycle;

public enum AgentState {

    CREATED,     // 已创建，未启动
    RUNNING,     // 运行中
    SUSPENDED,   // 已挂起，等待外部事件唤醒
    RETRYING,    // 重试中
    WAITING,     // 等待下一次定时触发（周期任务）
    COMPLETED,   // 已成功完成（终态）
    FAILED,      // 已失败（终态）
    CANCELLED;   // 已取消（终态）

    /** 是否为终态。终态不允许再流转到任何其他状态。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** 是否为"活跃"状态（进程崩溃后需被 Recovery 扫描恢复）。 */
    public boolean isActive() {
        return !isTerminal();
    }
}
```

**设计要点**：把 `isTerminal()` / `isActive()` 作为枚举方法内聚在状态自身，而非散落在外部工具类。这是"充血枚举"的做法——让状态自己回答关于自己的问题，符合面向对象的封装原则。

### 2.8 非法流转异常

文件：`day12longrunningagent/lifecycle/IllegalStateTransitionException.java`

```java
package com.zero.ai.agentstudy.day12longrunningagent.lifecycle;

public class IllegalStateTransitionException extends RuntimeException {

    private final AgentState from;
    private final AgentState to;

    public IllegalStateTransitionException(AgentState from, AgentState to) {
        super("非法状态流转: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public AgentState getFrom() { return from; }
    public AgentState getTo() { return to; }
}
```

**设计要点**：异常携带 `from` / `to`，便于上层监控与日志精确记录"是哪个非法流转被拦截"，而不是一句笼统的报错。

### 2.9 状态机核心

文件：`day12longrunningagent/lifecycle/AgentStateMachine.java`

```java
package com.zero.ai.agentstudy.day12longrunningagent.lifecycle;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class AgentStateMachine {

    private static final Map<AgentState, Set<AgentState>> TRANSITIONS = new EnumMap<>(AgentState.class);

    static {
      TRANSITIONS.put(AgentState.CREATED, EnumSet.of(
                AgentState.RUNNING, AgentState.CANCELLED));

        TRANSITIONS.put(AgentState.RUNNING, EnumSet.of(
                AgentState.RUNNING,      // 推进到下一步仍是 RUNNING（自环）
                AgentState.SUSPENDED, AgentState.RETRYING, AgentState.WAITING,
                AgentState.COMPLETED, AgentState.FAILED, AgentState.CANCELLED));

        TRANSITIONS.put(AgentState.SUSPENDED, EnumSet.of(
                AgentState.RUNNING, AgentState.CANCELLED, AgentState.FAILED));

        TRANSITIONS.put(AgentState.RETRYING, EnumSet.of(
                AgentState.RUNNING, AgentState.FAILED, AgentState.CANCELLED));

        TRANSITIONS.put(AgentState.WAITING, EnumSet.of(
                AgentState.RUNNING, AgentState.CANCELLED));

        TRANSITIONS.put(AgentState.COMPLETED, EnumSet.noneOf(AgentState.class));
        TRANSITIONS.put(AgentState.FAILED, EnumSet.noneOf(AgentState.class));
        TRANSITIONS.put(AgentState.CANCELLED, EnumSet.noneOf(AgentState.class));
    }

    public boolean canTransit(AgentState from, AgentState to) {
        if (from == null || to == null) return false;
        Set<AgentState> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public AgentState transit(AgentState from, AgentState to) {
        if (!canTransit(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
        return to;
    }

    public Set<AgentState> allowedTargets(AgentState from) {
        Set<AgentState> allowed = TRANSITIONS.get(from);
        return allowed == null ? EnumSet.noneOf(AgentState.class) : EnumSet.copyOf(allowed);
    }
}
```

**代码解析**：

- `TRANSITIONS` 用 `static` 块一次性初始化，整个 JVM 共享一份规则表，无实例状态，因此 `AgentStateMachine` 是**线程安全**的，可直接作为 Spring 单例注入。
- `canTransit` 只查表、不抛异常，适合"先判断再决定"的场景。
- `transit` 校验失败即抛异常，适合"流转必须成功、否则中断"的强约束场景。Runtime 在真正改状态时用它。
- `allowedTargets` 返回**防御性拷贝**（`EnumSet.copyOf`），防止调用方意外修改内部规则表。
- 终态用 `EnumSet.noneOf` 表示"无出边"，与 `isTerminal()` 语义呼应，形成双重保障。

### 2.10 使用示例

```java
AgentStateMachine sm = new AgentStateMachine();

// 合法：CREATED -> RUNNING
AgentState s1 = sm.transit(AgentState.CREATED, AgentState.RUNNING);   // 返回 RUNNING

// 合法：RUNNING -> SUSPENDED（等审批）
AgentState s2 = sm.transit(AgentState.RUNNING, AgentState.SUSPENDED); // 返回 SUSPENDED

// 非法：COMPLETED -> RUNNING（终态无出边）
try {
    sm.transit(AgentState.COMPLETED, AgentState.RUNNING);
} catch (IllegalStateTransitionException e) {
    // e.getMessage() = "非法状态流转: COMPLETED -> RUNNING"
}
```

### 2.11 如何在 Spring Boot 4 中注册

在配置类中声明为 Bean（Spring AI 2 场景无特殊要求，普通 `@Bean` 即可）：

```java
@Configuration
public class Day12RuntimeConfig {
    @Bean
    public AgentStateMachine agentStateMachine() {
        return new AgentStateMachine();
    }
}
```

后续的 `AgentRuntime`（Ch08）将注入它，在每次改状态前调用 `transit` 做校验。

---

## 第四部分 · 真实项目

- **AWS Step Functions**：整个产品就是一个"可视化状态机 + 持久化执行"服务，每个 State 有明确的 Next/Retry/Catch 规则，与我们的流转表同构。
- **Temporal**：其 Workflow 执行本质是事件溯源 + 确定性重放，状态流转由框架强约束，开发者无法非法跳转。
- **企业审批系统**：请假单的"草稿→提交→审批中→通过/驳回"就是典型状态机，一旦"通过"就是终态，不可回退到"审批中"——与我们 `COMPLETED` 无出边完全一致。
- **订单系统**：待支付→已支付→已发货→已完成/已退款，每一步都严格受状态机约束，防止"已完成订单被重复发货"这类资损事故。

这些系统证明：**状态机不是学院派概念，而是一切有状态业务系统的工程刚需。**

---

## 第五部分 · 避坑

1. **坑：用字符串存状态。** 拼写漂移、无编译期检查。→ 用枚举 `AgentState`。
2. **坑：状态流转规则散落在业务代码 if-else 里。** 无法审计、易漏。→ 集中到状态机流转表。
3. **坑：允许业务代码直接 `session.setState(...)` 裸改。** 绕过状态机 = 埋雷。→ 所有变更走 `transit`，`setState` 收紧为包内可见或由 Runtime 统一调用。
4. **坑：忘记给终态设"无出边"。** 导致已完成任务被重新拉起重复执行。→ 显式 `EnumSet.noneOf`。
5. **坑：`allowedTargets` 直接返回内部集合引用。** 调用方可篡改规则表。→ 返回 `EnumSet.copyOf` 防御性拷贝。
6. **坑：状态机持有可变实例字段。** 破坏线程安全。→ 规则表用 `static final`，状态机无实例状态。
7. **坑：把"重试"和"运行"混为一谈。** 无法区分"正常执行"与"失败重试中"，监控失真。→ 独立 `RETRYING` 状态。
8. **坑：把"等审批"和"等定时"用同一个状态。** 恢复逻辑无法区分该等什么。→ 用 `SUSPENDED`（等外部事件）与 `WAITING`（等定时）区分。
9. **坑：状态机抛出裸 `RuntimeException`。** 上层无法精准处理。→ 用专用 `IllegalStateTransitionException` 携带 from/to。
10. **坑：状态定义随意扩张。** 状态越多、组合爆炸越严重。→ 保持状态最小完备集，宁可用 Context 字段细分，也不轻易新增状态。

---

## 本章小结

- Runtime 的第一职责是把"状态"升级为受状态机约束的一等公民。
- 状态机 = 一张"源状态→合法目标状态集合"的转移表，用 `EnumMap`/`EnumSet` 高效实现。
- 8 个状态覆盖长任务全生命周期，3 个终态无出边。
- 所有状态变更必须经过 `transit` 校验，杜绝非法流转。

### 面试题

1. 为什么用状态机而不是 if-else 管理状态流转？
2. 为什么 `AgentStateMachine` 是线程安全的？如何保证的？
3. `SUSPENDED` 和 `WAITING` 有什么区别？为什么要区分？
4. 终态为什么必须"无出边"？不这么做会有什么后果？
5. `EnumMap`/`EnumSet` 相比 `HashMap`/`HashSet` 在枚举场景有何优势？

### 扩展阅读

- Spring Statemachine 官方文档
- AWS Step Functions：States Language 规范
- 《Effective Java》Item 34–37（枚举与 EnumMap/EnumSet）

---

> ✅ 本章完成。请输入"**继续**"，进入 Chapter 03：Session 与 State 持久化（AgentSession / AgentContext / Store，含完整 Java 代码）。