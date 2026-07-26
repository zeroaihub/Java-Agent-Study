# 第三章：Workflow 设计模式

> 第二章拆了七大组件，本章回答：为什么 Workflow 的实现几乎必然用到这几种设计模式？
> 掌握本章，你写出的引擎才「可扩展、可插拔、可维护」，而不是又一个巨型 if-else。

---

## 第一部分：为什么学（核心价值）

一个真实问题：Workflow 引擎最怕什么？——**怕变化**。
- 今天 5 个节点，明天要加到 15 个；
- 今天顺序执行，明天要条件分支；
- 今天用 A 酒店源，明天换 B；
- 今天要重试，明天要审计日志。

如果用「巨型 if-else + switch」硬写，每次变化都要改核心代码，牵一发动全身。
**设计模式的本质就是「隔离变化」**——把「会变的部分」和「不变的部分」分开。
Workflow 是「变化密集型」系统，所以它是设计模式的天练兵场。

**为什么 Workflow 大量使用设计模式？**
因为它的每个组件都对应一个经典「变化点」：
- 节点会增减 → 责任链 / 命令模式
- 状态会流转 → 状态模式
- 单步实现会替换 → 策略模式
- 数据要逐级加工 → Pipeline 模式

---

## 第二部分：是什么（五种模式 + Workflow 映射）

| 设计模式 | 解决的变化 | 在 Workflow 中的角色 |
|----------|-----------|---------------------|
| 责任链 Chain of Responsibility | 处理步骤增减、顺序调整 | Node 一个接一个处理，可动态编排 |
| 状态模式 State | 不同状态行为不同 | CREATED/RUNNING/FAILED 各有行为，自动流转 |
| 策略模式 Strategy | 同一步骤多种实现 | 一个 Node 内可切换算法（不同酒店源/LLM） |
| 命令模式 Command | 操作要排队/记录/撤销/重试 | 把「执行一个 Node」封装成命令对象 |
| Pipeline 管道 | 数据逐级加工 | Context 像水流过一节节管道 |

---

## 1. 责任链模式（Chain of Responsibility）

**定义**：把多个处理器串成链，请求沿链传递，每个处理器决定「自己处理」或「传给下一个」。

**Workflow 映射**：每个 Node 是链上一环，做完自己的事，把 Context 交给下一个 Node。
新增/删除/重排节点，只需改「链的组装」，不改 Node 本身 → **开闭原则**。

```java
// 责任链风格：每个 Node 处理后交给 next
public abstract class ChainNode {
    protected ChainNode next;                 // 下一个处理器
    public ChainNode setNext(ChainNode n) { this.next = n; return n; }

    /** 模板方法：先处理自己，再传递 */
    public void handle(WorkflowContext ctx) {
        doHandle(ctx);                        // 处理本环
        if (next != null && !ctx.isStopped()) {
            next.handle(ctx);                 // 传给下一环
        }
    }
    protected abstract void doHandle(WorkflowContext ctx);
}

// 组装：weather -> hotel -> plan，想加节点只改这一行
weather.setNext(hotel).setNext(plan);
```

**为什么用它**：节点的「顺序」是最易变的部分，责任链把顺序从节点内部剥离到「组装处」。

---

## 2. 状态模式（State）

**定义**：对象内部状态改变时，行为随之改变，看起来像换了个类。把「状态相关行为」封装进各状态对象。

**Workflow 映射**：Workflow 实例的 CREATED / RUNNING / SUSPENDED / COMPLETED / FAILED，
每个状态下「能做什么」不同（如 FAILED 不能再推进，SUSPENDED 只能被唤醒）。

```java
public interface WorkflowState {
    void next(WorkflowInstance wf);   // 该状态下如何流转
    boolean canRun();                 // 该状态是否可执行节点
}

public class RunningState implements WorkflowState {
    public void next(WorkflowInstance wf) {
        if (wf.hasNextNode()) wf.setState(new RunningState());
        else wf.setState(new CompletedState());
    }
    public boolean canRun() { return true; }
}

public class FailedState implements WorkflowState {
    public void next(WorkflowInstance wf) { /* 终态，不流转 */ }
    public boolean canRun() { return false; }   // 失败态禁止执行
}
```

**为什么用它**：避免「到处 if (state==X)」。状态判断集中到状态类，新增状态不改主流程。

---

## 3. 策略模式（Strategy）

**定义**：定义一系列算法，封装起来使它们可互换。算法的变化不影响使用者。

**Workflow 映射**：同一个「查酒店」节点，可能有「携程源 / 美团源 / 模拟源」多种实现；
「生成计划」可能用「规则引擎 / LLM」。用策略接口隔离，运行时可切换。

```java
public interface HotelStrategy {
    List<String> queryHotels(String city);   // 统一契约
}

@Component("mockHotel")
public class MockHotelStrategy implements HotelStrategy {
    public List<String> queryHotels(String city) {
        return List.of(city + "·如家", city + "·全季");
    }
}

// HotelNode 依赖接口而非具体实现，可注入不同策略
public class HotelNode implements WorkflowNode {
    private final HotelStrategy strategy;     // 面向接口
    public HotelNode(HotelStrategy strategy) { this.strategy = strategy; }
    public NodeResult execute(WorkflowContext ctx) {
        ctx.put("hotels", strategy.queryHotels((String) ctx.get("city")));
        return NodeResult.success();
    }
}
```

**为什么用它**：「一个步骤怎么实现」是易变点。策略模式让实现可插拔，符合依赖倒置。

---

## 4. 命令模式（Command）

**定义**：把「一个请求/操作」封装成对象，从而可以参数化、排队、记录日志、撤销、重试。

**Workflow 映射**：引擎不直接 `node.execute()`，而是包一层 `NodeCommand`。
这样「执行一个节点」就变成一个可被引擎统一处理的对象——可加重试、超时、日志、撤销。

```java
// 命令：把"执行某节点"封装成对象
public class NodeCommand {
    private final WorkflowNode node;
    private final WorkflowContext ctx;
    public NodeCommand(WorkflowNode node, WorkflowContext ctx) {
        this.node = node; this.ctx = ctx;
    }
    /** 引擎统一调用 execute()，横切能力都加在这里 */
    public NodeResult execute() {
        long t0 = System.currentTimeMillis();
        NodeResult r = node.execute(ctx);           // 真正动作
        ctx.addLog(node.name(), r, System.currentTimeMillis() - t0);  // 记录
        return r;
    }
}
```

**为什么用它**：重试、超时、日志、审计这些「横切关注点」，用命令对象统一挂载，
Node 只写业务，横切能力集中治理（这正是第六章要做的事）。

---

## 5. Pipeline（管道 / 流水线）模式

**定义**：数据依次流经一系列处理阶段，每阶段对数据做一次转换。

**Workflow 映射**：Context 就是流经管道的「数据」，每个 Node 是一个 stage，
读上一阶段产出、写本阶段结果。整体像一条流水线。

```java
// 函数式 Pipeline：每个 stage 是 Context -> Context
List<Function<WorkflowContext, WorkflowContext>> pipeline = List.of(
    ctx -> { ctx.put("weather", "晴"); return ctx; },   // 查天气
    ctx -> { ctx.put("hotels", "如家"); return ctx; },  // 查酒店
    ctx -> { ctx.put("plan", "户外游"); return ctx; }   // 生成计划
);
WorkflowContext ctx = new WorkflowContext();
for (var stage : pipeline) ctx = stage.apply(ctx);      // 数据逐级流过
```

**为什么用它**：强调「数据的逐级加工」，适合 ETL、RAG 索引构建等数据密集场景。

---

## 五种模式协作全景

```
          命令模式(NodeCommand 包装每次执行, 挂日志/重试)
                          │
   责任链(串联Node顺序) ───┼─── 状态模式(管理实例CREATED→RUNNING→...)
                          │
          策略模式(Node内部可插拔具体实现)
                          │
          Pipeline(Context数据逐级加工流过所有Node)
```

- **责任链** 管「节点之间怎么串」；
- **状态模式** 管「实例整体在哪个阶段」；
- **策略模式** 管「单个节点内部怎么实现」；
- **命令模式** 管「每次执行如何被统一治理」；
- **Pipeline** 管「数据如何逐级流动」。

五者不是互斥，而是**分工协作**，共同支撑一个可扩展的 Workflow 引擎。

---

## 第三部分：怎么用（本章为设计思想，代码在四~六章落地）

- 第四章：用「责任链 + 顺序 Node」实现旅行规划；
- 第五章：抽出 `WorkflowNode` 接口 + `NodeResult`（策略 + 命令雏形；
- 第六章：用命令模式挂重试/超时/日志；用状态模式管实例状态。

---

## 第四部分：用在哪（真实项目）

- **AI 审批流**：状态模式（草稿→审批中→通过/驳回）。
- **AI 客服**：责任链（敏感词过滤→意图识别→检索→回复）。
- **多模型路由**：策略模式（GPT/Claude/本地模型可切换）。
- **可撤销的批处理**：命令模式（记录命令，失败可回滚）。
- **RAG 索引构建**：Pipeline（读取→切分→向量化→入库）。

---

## 第五部分：避坑与优化

1. **过度设计**：小 Demo 不必五种模式全上，按变化点按需引入。
2. **责任链忘记终止条件**：ctx.isStopped() / 最大步数，防死循环。
3. **状态模式状态爆炸**：状态别拆太细，5~6 个足够覆盖主流程。
4. **策略模式滥用 Spring Bean**：策略太多时用工厂 + Map 管理，别硬编码 if。
5. **命令模式和 Node 职责混淆**：命令只做「横切治理」，业务永远在 Node 里。

---

## 本章总结

- Workflow 是「变化密集型」系统，设计模式用来隔离变化。
- 五种核心模式：责任链（串联）、状态（阶段）、策略（可插拔实现）、命令（横切治理）、Pipeline（数据流）。
- 它们分工协作，共同支撑可扩展引擎——这是第五、六章的设计蓝图。

---

## 核心概念

| 模式 | 一句话 | 隔离的变化 |
|------|--------|-----------|
| 责任链 | Node 串成链传递 | 节点顺序/增减 |
| 状态 | 状态决定行为 | 实例阶段 |
| 策略 | 算法可互换 | 单步实现 |
| 命令 | 操作封装成对象 | 横切能力 |
| Pipeline | 数据逐级加工 | 数据处理阶段 |

---

## 常见错误

- 把「策略模式」和「责任链」搞混：策略是「一步的多种实现」，责任链是「多步的串联」。
- 用 `instanceof` 判断节点类型——违背面向接口，应交给多态/策略。

---

## 面试问题

1. Workflow 为什么大量使用设计模式？本质是隔离什么？
2. 责任链和 Pipeline 有什么区别与联系？
3. 命令模式在 Workflow 里解决了什么问题？
4. 状态模式如何避免「满屏 if (state==X)」？

---

## 本章练习

**问：如果要给「查酒店」这一步支持「携程 / 美团 / 模拟」三种数据源，随时可切换，你会用哪种设计模式？为什么？请写出接口定义。**

**参考答案：** 用**策略模式**。因为「同一步骤的多种可互换实现」正是策略模式的应用场景，它把「查酒店」的契约（接口）与具体实现分离，运行时可注入不同策略，符合开闭原则与依赖倒置。
```java
public interface HotelStrategy { List<String> queryHotels(String city); }
// CtripHotelStrategy / MeituanHotelStrategy / MockHotelStrategy 各自实现
// HotelNode 依赖 HotelStrategy 接口，通过构造注入具体策略
```