# 第五章：造一个 Workflow 引擎（WorkflowEngine V1）

> 上一章拆出了 5 个散落的节点。本章造出「发动机」，让它们按顺序跑起来，
> 并统一处理状态流转——这就是整个框架的心脏。

---

## 第一部分：为什么学（核心价值）

如果每个 Agent 都自己写「先调 A，再调 B，出错怎么办」，那就是 N 份重复的调度代码。
**引擎的价值：把「调度逻辑」抽出来写一次，所有流程复用。**

引擎只依赖 `WorkflowNode` 接口，不认识任何具体业务。
这意味着：同一个引擎既能跑旅行规划，也能跑订单审批——只要把节点换一批。
这是控制反转(IoC)与开闭原则(OCP)在流程层的终极体现。

---

## 第二部分：是什么（引擎的两大职责）

1. **责任链驱动**：按 List 顺序依次执行节点。
2. **状态机管理**：根据每个节点返回的 `NodeResult`，把流程宏观状态在
   `CREATED → RUNNING → COMPLETED/FAILED/SUSPENDED` 之间流转。

引擎依据 `NodeStatus` 做四种决策：
| NodeStatus | 引擎动作 |
|---|---|
| SUCCESS | 推进到下一个节点 |
| COMPLETED | 流程正常结束 |
| FAILED | 终止流程，状态置 FAILED |
| SUSPENDED | 暂停流程，等待人工 |

---

## 第三部分：怎么用（核心代码）

引擎主循环（见 `workflow/engine/WorkflowEngine.java`）：

```java
public WorkflowResult run(List<WorkflowNode> nodes, WorkflowContext context) {
    context.setState(WorkflowState.RUNNING);
    for (WorkflowNode node : nodes) {
        NodeResult result = executeWithRetry(node, context, logs); // 带重试执行

        if (result.getStatus() == NodeStatus.FAILED) {
            context.setState(WorkflowState.FAILED);
            return buildResult(context, logs);      // 失败即止
        }
        if (result.getStatus() == NodeStatus.COMPLETED) {
            context.setState(WorkflowState.COMPLETED);
            return buildResult(context, logs);      // 正常结束
        }
        context.advance();  // SUCCESS：推进
    }
    context.setState(WorkflowState.COMPLETED);
    return buildResult(context, logs);
}
```

**设计要点**：
- 引擎不 new 节点，节点由 Spring 注入、由 `TravelWorkflowConfig` 装配成 List 传进来。
- 引擎不关心节点里做什么，只看它返回的状态——这就是「面向接口」。

节点如何被装配（见 `config/TravelWorkflowConfig.java`）：

```java
@Bean("travelWorkflowNodes")
public List<WorkflowNode> travelWorkflowNodes(
        InputCityNode a, WeatherNode b, HotelNode c, PlanNode d, OutputNode e) {
    return List.of(a, b, c, d, e);  // 顺序即流程
}
```

想调整流程？只改这个 List 的顺序，引擎一行不动。

---

## 第四部分：Python 参考

```python
def run(nodes, ctx):
    ctx["state"] = "RUNNING"
    for node in nodes:
        result = execute_with_retry(node, ctx)
        if result.status == "FAILED":
            ctx["state"] = "FAILED"; break
        if result.status == "COMPLETED":
            ctx["state"] = "COMPLETED"; break
    else:
        ctx["state"] = "COMPLETED"
    return build_result(ctx)
```

LangGraph 用 `StateGraph.add_edge(a, b)` 声明连接、`.compile()` 得到可执行图，
本质是同一套「节点 + 有向边 + 状态驱动」。

---

## 第五部分：用在哪 + 避坑优化

**用在哪**：所有需要「统一调度多步骤」的场景。一套引擎 + 多套节点配置 = 多个 Agent。

**常见坑**：
1. **引擎里 if 判断具体节点类型** → 破坏解耦。引擎只能依赖接口和状态枚举。
2. **节点抛异常直接冒泡** → 整个引擎崩溃。引擎必须 try-catch 包装成 `NodeResult.fail`（本例已做）。
3. **顺序写死在引擎里** → 失去可编排性。顺序应外置到配置。

**优化方向（后续章节）**：
- 条件分支：由当前 NodeResult 决定跳到哪个节点（有向图而非直线）；
- 并发执行：无依赖节点并行跑；
- 断点恢复：Context 记录 currentIndex，失败后从中断处续跑。

---

## 面试问题

1. 引擎为什么只能依赖 `WorkflowNode` 接口，不能依赖具体节点类？
2. `NodeStatus`（节点状态）和 `WorkflowState`（流程状态）有什么区别？
3. 节点抛出未捕获异常时，引擎应如何处理才不至于整体崩溃？

---

## 练习答案（参考）

> 练习：让引擎支持「某节点失败时跳过而非终止」。
> 参考：给 `WorkflowNode` 加 `default boolean skippable(){return false;}`，
> 引擎遇到 FAILED 且 `node.skippable()` 为 true 时，记录日志后 `context.advance()` 继续，而非 return。

---

> 下一章：引擎目前只有基础重试。企业级还需要超时、异常兜底、条件分支、完整审计日志——第六章补齐。