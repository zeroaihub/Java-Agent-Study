# 第六章：给引擎加装甲（重试 · 异常兜底 · 执行日志）

> 第五章的引擎能跑通「顺风顺水」的流程。但真实世界里，天气 API 会超时、
> 酒店服务会抖动、节点会抛异常。本章让引擎在故障面前依然稳健、且全程可追溯。

---

## 第一部分：为什么学（核心价值）

Demo 和生产的差距，90% 在「异常路径」上：
- 一次网络抖动不该让整个流程失败 → **重试**；
- 一个节点 throw 不该让引擎崩溃 → **异常兜底**；
- 线上出问题要能查「哪个节点、跑了几次、耗时多久」 → **执行日志（可观测）**。

这三件事是「能演示」到「敢上线」的分水岭。

---

## 第二部分：是什么

| 能力 | 解决什么 | 对应实现 |
|---|---|---|
| 重试 Retry | 瞬时故障（网络抖动） | `executeWithRetry` + `WorkflowNode.maxRetries()` |
| 异常兜底 | 节点抛未捕获异常 | try-catch 包装成 `NodeResult.fail` |
| 执行日志 | 事后审计、性能定位 | `WorkflowExecutionLog`（节点名/状态/重试次数/耗时） |

---

## 第三部分：怎么用（核心代码）

带重试与兜底的执行（见 `WorkflowEngine.executeWithRetry`）：

```java
private NodeResult executeWithRetry(WorkflowNode node, WorkflowContext ctx,
                                    List<WorkflowExecutionLog> logs) {
    int max = node.maxRetries();     // 节点自己声明能重试几次
    int attempt = 0;
    long start = System.currentTimeMillis();
    NodeResult result;
    while (true) {
        attempt++;
        try {
            result = node.execute(ctx);            // 正常执行
        } catch (Exception e) {                    // 兜底：异常不外抛
            result = NodeResult.fail("节点异常: " + e.getMessage());
        }
        if (result.getStatus() != NodeStatus.FAILED || attempt > max) {
            break;                                 // 成功 或 重试耗尽 → 退出
        }
        // 失败且还有重试次数 → 再来一次
    }
    long cost = System.currentTimeMillis() - start;
    logs.add(new WorkflowExecutionLog(node.name(), result.getStatus(), attempt, cost));
    return result;
}
```

**三层防护**：
1. `try-catch` 把任何异常收敛成 FAILED，引擎主循环永不崩；
2. `while + attempt` 在 FAILED 时按 `maxRetries()` 重试；
3. 无论成败都 `logs.add(...)` 记一条审计。

节点声明自己的重试策略（见 `WeatherNode`）：

```java
@Override
public int maxRetries() {
    return 2;   // 天气查询允许重试 2 次；不需要重试的节点默认返回 0
}
```

执行日志的样子（见 `WorkflowExecutionLog.toReadableLine`）：

```
[INPUT_CITY]   SUCCESS   attempt=1  cost=1ms
[WEATHER]      SUCCESS   attempt=2  cost=53ms   ← 第一次失败重试后成功
[HOTEL]        SUCCESS   attempt=1  cost=12ms
[PLAN]         SUCCESS   attempt=1  cost=0ms
[OUTPUT]       COMPLETED attempt=1  cost=1ms
```

---

## 第四部分：Python 参考

```python
def execute_with_retry(node, ctx):
    for attempt in range(1, node.max_retries + 2):
        try:
            result = node.execute(ctx)
        except Exception as e:
            result = fail(f"节点异常: {e}")
        if result.status != "FAILED":
            return result, attempt
    return result, attempt   # 重试耗尽
```

Tenacity 库的 `@retry(stop=stop_after_attempt(3))` 是同一思路的装饰器封装。

---

## 第五部分：用在哪 + 避坑优化

**用在哪**：任何依赖外部 IO 的节点（LLM 调用、HTTP、DB）都应配重试 + 日志。

**常见坑**：
1. **无脑重试非幂等操作**（如「已下单」）→ 重复扣款。只对幂等/只读操作重试。
2. **重试无间隔（狂刷）** → 把下游打垮。应加**指数退避**（sleep 1s、2s、4s）。
3. **catch(Exception) 后吞掉不记日志** → 线上盲查。异常必须落日志。
4. **maxRetries 写死在引擎** → 不同节点需求不同。应由节点自己声明（本例做法）。

**优化方向**：
- 指数退避 + 抖动（jitter）避免重试风暴；
- 超时控制：`Future.get(timeout)` 防止节点永久阻塞；
- 熔断：连续失败达阈值后直接快速失败，不再重试。

---

## 面试问题

1. 为什么「重试次数」应由节点声明而非引擎统一写死？
2. 哪些操作绝对不能重试？如何用「幂等」判断？
3. 生产环境的重试为什么必须加退避（backoff），直接循环重试会有什么后果？

---

## 练习答案（参考）

> 练习：给 `executeWithRetry` 加指数退避。
> 参考：在「失败且还要重试」的分支里加
> `Thread.sleep((long) Math.pow(2, attempt) * 100);`
> 即第 1 次退避 200ms、第 2 次 400ms……生产中还需叠加随机 jitter 防同步重试。

---

> 下一章：引擎已足够健壮。第七章跳出代码，站在架构视角谈「企业级 Workflow 平台」还需要什么——配置化编排、版本管理、Human-in-the-loop、可观测体系。