# 第二章：Workflow 底层原理

> 第一章讲「为什么」，本章讲「是什么 + 底层怎么跑」。学完你要能默画出完整生命周期流程图。

---

## 第一部分：为什么学（核心价值）

只有理解底层原理，你才能：
- 自己实现一个 Workflow 引擎（第五章要做的事）；
- 看懂 LangGraph / Temporal / Dify 的设计；
- 在面试中说清「一个 Workflow 是怎么被驱动执行的」；
- 判断什么该放 Node、什么该放引擎、什么该放 Context。

不懂原理就用框架，出问题只能干瞪眼；懂原理，框架只是选择题。

---

## 第二部分：是什么（七大核心组件 + 生命周期）

Workflow 引擎运转，靠七个核心组件协作：

### 1. Node（节点）——最小执行单元
- **职责**：只做一件事（查天气、查酒店、生成计划）。
- **输入**：Context（读取前面步骤的产出）。
- **输出**：NodeResult（本步结果 + 下一步去哪）。
- **原则**：单一职责、无副作用地依赖成员变量（状态走 Context）。

```java
public interface WorkflowNode {
    String name();                 // 节点名（日志/路由用）
    NodeResult execute(WorkflowContext ctx);  // 执行动作
}
```

### 2. Edge（边 / Transition 转移）——决定下一步
- **职责**：连接两个 Node，回答「这一步做完去哪」。
- **两种形态**：
  - 顺序边：A 做完固定去 B；
  - 条件边：A 做完，根据 Context 判断去 B 还是 C。
- **实现方式**：可以是「Node 的 next 字段」，也可以是引擎维护的「路由表 Map<from, to>」，还可以是「Condition 函数」。

### 3. State（状态）——工作流实例的宏观状态
- CREATED → RUNNING → COMPLETED / FAILED / SUSPENDED
- 描述整个 Workflow 实例「现在处于哪个阶段」，用于恢复、监控。

### 4. Context（上下文）——共享数据袋
- **职责**：贯穿全流程的读写空间，Node 从这里读输入、写输出。
- **本质**：一个 `Map<String,Object>` + 一些元信息（实例 id、当前节点、日志）。
- **为什么显式而非成员变量**：显式 Context 才能被序列化、持久化、跨线程传递、断点恢复。

### 5. Action（动作）——Node 内真正干的活
- 调 LLM、调 Tool（Day3）、查 RAG（Day5）、读写 Memory（Day4）、访问 DB。
- Action 是「业务」，Node 是「业务的容器」，引擎是「容器的调度者」。三者分层。

### 6. Transition（转移逻辑）——引擎如何选下一个 Node
- 引擎拿到 NodeResult，读取其中的 `nextNode`（或用路由表 + Condition 计算），
  找到下一个要执行的 Node，继续循环，直到没有下一步或失败。

### 7. Execution Engine（执行引擎）——总指挥
- **职责**：
  1. 从起始 Node 开始；
  2. 执行当前 Node 的 Action；
  3. 拿到 NodeResult；
  4. 记录 ExecutionLog（耗时/输入/输出/状态）；
  5. 处理失败：重试 / 超时 / 降级；
  6. 根据 Transition 找到下一个 Node；
  7. 回到第 2 步，直到 COMPLETED 或 FAILED。

---

## 完整生命周期 ASCII 流程图

```
┌────────────────────────────────────────────────────────────────┐
│                      WORKFLOW ENGINE 主循环                       │
└────────────────────────────────────────────────────────────────┘

   START
     │
     ▼
 ┌─────────┐  初始化 Context（写入初始输入）
 │ CREATED │  state = CREATED
 └────┬────┘
      │  engine.run(startNode)
      ▼
 ┌─────────┐  state = RUNNING，currentNode = startNode
 │ RUNNING │◀───────────────────────────┐
 └────┬────┘                             │
      │ 取 currentNode                    │
      ▼                                   │
 ┌────────────────────┐                  │
 │ 执行 Node.execute() │                  │
 │  (读 Context→做Action│                  │
 │   →写 Context)       │                  │
 └────┬───────────────┘                  │
      │                                 │
   出现异常?──是──▶┌──────────────┐        │
      │否          │ 可重试且未用尽?│─是─▶ backoff 等待
      │            └──────┬───────┘        │ 重新执行本 Node
      │                   │否               │
      │                   ▼                 │
      │            ┌──────────────┐         │
      │            │ state=FAILED │         │
      │            │  记录错误日志 │         │
      │            └──────┬───────┘         │
      │                   ▼                 │
      │                 END(失败)            │
      ▼                                     │
 ┌─────────────────┐                        │
 │ 记录 ExecutionLog│                        │
 │ (耗时/IN/OUT/OK) │                        │
 └────┬────────────┘                        │
      ▼                                     │
 ┌──────────────────────┐                   │
 │ 有 nextNode ?         │──有──▶ currentNode = nextNode ─┘
 │ (读 NodeResult/路由表) │
 └────┬─────────────────┘
      │无（流程结束）
      ▼
 ┌───────────┐
 │ COMPLETED │  取 Context.result 作为最终输出
 └─────┬─────┘
       ▼
      END(成功)

（可选）RUNNING 中若遇 Human-in-the-loop 节点：
   state = SUSPENDED → 持久化 Context → 等待人工输入 → 回到 RUNNING
```

---

## 第三部分：怎么用（Java / Python 实现思路）

### Java 实现思路（第五章将落地）

```java
// 引擎主循环（伪代码，第五章会完整实现并带注释）
public WorkflowContext run(WorkflowContext ctx, WorkflowNode start) {
    ctx.setState(State.RUNNING);
    WorkflowNode current = start;
    while (current != null) {
        long t0 = System.currentTimeMillis();
        try {
            NodeResult r = executeWithRetry(current, ctx);   // 含重试/超时
            ctx.addLog(current.name(), r, System.currentTimeMillis() - t0);
            if (!r.isSuccess()) { ctx.setState(State.FAILED); break; }
            current = resolveNext(current, r, ctx);           // Transition
        } catch (Exception e) {
            ctx.setState(State.FAILED); break;
        }
    }
    if (ctx.getState() == State.RUNNING) ctx.setState(State.COMPLETED);
    return ctx;
}
```

### Python 参考实现思路

```python
def run(ctx, start_node):
    ctx.state = "RUNNING"
    current = start_node
    while current is not None:
        t0 = time.time()
        try:
            result = execute_with_retry(current, ctx)      # 含重试/超时
            ctx.add_log(current.name, result, time.time() - t0)
            if not result.success:
                ctx.state = "FAILED"; break
            current = resolve_next(current, result, ctx)    # Transition
        except Exception as e:
            ctx.state = "FAILED"; break
    if ctx.state == "RUNNING":
        ctx.state = "COMPLETED"
    return ctx
```

**Java vs Python 思路一致**，差别只在语言特性；企业里 Java 侧常结合 Spring 的 IoC 注入 Node、AOP 做日志/重试。

### 企业实现方式

- 用**图结构**（邻接表/DAG）表达 Node+Edge，而非 while 链；
- Context 序列化进 Redis/DB，支持**断点恢复**；
- 重试/超时/日志用**引擎统一治理**（横切）；
- 并行节点用**线程池 / CompletableFuture**；
- 大型场景直接用 Temporal / Flowable / LangGraph。

---

## 第四部分：用在哪（真实项目）

- **AI 审批流**：State 机天然对应 CREATED→审批中→通过/驳回；SUSPENDED 对应等人审批。
- **AI 数据管道**：Node 是 ETL 的每一步，Context 携带中间数据集。
- **AI 客服**：Transition + Condition 决定「查库 or 转人工」。

---

## 第五部分：避坑与优化

1. **Node 里改成员变量**——多线程/恢复时数据错乱，状态必须进 Context。
2. **引擎和 Node 耦合**——引擎里出现 `if (node instanceof WeatherNode)` 是灾难，要面向接口。
3. **NodeResult 结构不统一**——引擎无法统一处理成功/失败/跳转。
4. **日志散落**——应由引擎统一记录 ExecutionLog，而不是每个 Node 自己打。
5. **while 死循环**——条件边设计错误会导致节点来回跳，需加最大步数保护。

---

## 本章总结

- 七大组件：Node、Edge、State、Context、Action、Transition、Engine。
- 生命周期：CREATED → RUNNING →（执行 Node→记日志→重试→找下一步）→ COMPLETED/FAILED。
- 引擎是「主循环」，Context 是「数据袋」，Node 是「执行单元」，Transition 是「路由」。

---

## 核心概念

| 组件 | 职责 | 类比 |
|------|------|------|
| Node | 执行一步 | 工人 |
| Edge/Transition | 决定下一步 | 路标 |
| State | 实例宏观状态 | 项目进度 |
| Context | 共享数据 | 传递的工单 |
| Action | 真正干的活 | 工人的手艺 |
| Engine | 调度总指挥 | 工头 |

---

## 常见错误

- 把 State（实例状态）和 Context（数据）混为一谈。
- 认为引擎要「知道」每个 Node 是干嘛的——恰恰相反，引擎只认接口。

---

## 面试问题

1. 一个 Workflow 从开始结束，引擎内部发生了什么？（描述主循环）
2. State 和 Context 的区别？
3. 为什么重试/超时/日志要放引擎而非 Node？
4. 如何防止条件边导致的死循环？

---

## 本章练习

**请你默画一张「Workflow 完整生命周期流程图」**，要求包含：
CREATED、RUNNING、执行 Node、记录日志、重试判断、Transition下一步、COMPLETED、FAILED 八个要素。
（画完对照本章 ASCII 图自检。）