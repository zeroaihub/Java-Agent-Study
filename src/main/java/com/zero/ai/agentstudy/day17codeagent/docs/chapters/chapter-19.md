# Chapter 19 · 可观测性与预算护栏：看得见、刹得住

> 本章目标：给每个步骤自动埋点并找出瓶颈，给模型调用装上**事前**预算检查；理解为什么 `acquire*` 必须 `synchronized`，以及「步骤耗时」与「墙钟时间」的差值说明什么。

---

## 一、为什么要学（Why）

先讲一个真实事故形态：

> 一个自修复循环失控，跑了 20 轮 × 6 次模型调用 = 120 次调用。
> 按 20k token/次计算，一夜烧掉的钱是单次成本的 **120 倍**。

这不是假设——Ch13 已经证明「模型没听懂反馈」时会一直重试到轮次上限；Ch18 也证明了轮次给多了纯属浪费。**刹车必须存在，而且必须是硬刹车。**

第二个动机是排障。一次 Code Agent 任务经过十几步，出问题时如果只知道「任务失败」，你无法判断是：上下文选错了文件？补丁没解析出来？编译挂了？还是测试超时？**没有 Trace 就只能猜。**

---

## 二、是什么（What）

### 2.1 Trace：每一步都记账

```java
public record AgentStep(
        String name,
        Status status,        // SUCCESS / FAILED / SKIPPED / DEGRADED
        long elapsedMs,
        int tokens,
        String detail
) { }
```

`TaskTrace.timed()` 是个自动计时的包装器：

```java
public <T> T timed(String name, java.util.function.Supplier<T> body) {
    long start = System.currentTimeMillis();
    try {
        T value = body.get();
        record(new AgentStep(name, Status.SUCCESS, System.currentTimeMillis() - start, 0, null));
        return value;
    } catch (RuntimeException e) {
        record(new AgentStep(name, Status.FAILED, System.currentTimeMillis() - start, 0, e.getMessage()));
        throw e;
    }
}
```

它的价值在于：**失败的步骤也会被记录**。一个失败的任务，Trace 会显示它走到哪一步挂了——这比「任务失败」有用得多。

### 2.2 预算护栏：事前检查

```java
public synchronized void acquireModelCall(int estimatedTokens) {
    if (modelCalls + 1 > budget.maxModelCalls()) {
        reject("调用次数达到上限 " + budget.maxModelCalls() + "（QUOTA_EXCEEDED）");
    }
    if (usedTokens + estimatedTokens > budget.maxTokens()) {
        reject("token 预算不足：已用 " + usedTokens + "，本次需要 " + estimatedTokens
                + "，上限 " + budget.maxTokens() + "（BUDGET_EXCEEDED）");
    }
    checkWallClock();
    modelCalls++;
}
```

三个维度：**调用次数**（QUOTA）、**token 总量**（BUDGET）、**墙钟时间**（BUDGET）。

**「事前」是关键**：先检查再执行，而不是执行完记账。执行完才发现超预算，钱已经花了。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.observability.Chapter19Demo`

### 3.1 一次任务的完整时间线

```
=== [1] 一次任务的完整时间线 ===
Trace trace-demo-001 / 任务 task-multiply
  上下文选择          成功       93ms      0 token
  提示词装配          成功        0ms      0 token
  模型调用           成功        1ms   1514 token  model=stub-code-model-1.0
  补丁应用           成功       12ms      0 token  1/1 个应用成功
  编译验证           成功      524ms      0 token  编译通过
  ------------------------------------------------
  合计 5 步, 1514 token, 步骤耗时 630ms（墙钟 647ms）
  瓶颈步骤：编译验证 (524ms)
```

**瓶颈自动识别**：编译验证 524ms，占了全部步骤耗时的 83%。这个信息直接指导优化方向——想让任务更快，就得优化编译（Ch11 的片段编译正是为此）。

### 3.2 墙钟 vs 步骤耗时

```
=== [2] 预算护栏状态 ===
  BudgetGuard{token 1514/20000 (8%), 调用 1/6, 修复 0/3, 预计花费 ¥0.05, 647ms}
  → 注意「步骤耗时」与「墙钟时间」的差：
    差值就是没被 timed() 包住的部分（I/O、GC 等）。差太多说明埋点不够。
```

`步骤耗时 630ms` vs `墙钟 647ms`，差 17ms——很小，说明埋点覆盖良好。**如果差值很大，说明有相当比例的时间没被任何步骤记录**，那是埋点不足的信号，需要补。

### 3.3 Token 预算拦截

```
=== [3] 预算拦截：把 token 上限压到很低 ===
  第 1 次调用被拦下：
    token 预算不足：已用 0，本次需要 1281，上限 500（BUDGET_EXCEEDED）
  成功调用 0 次，累计 0 token，预计花费 ¥0.0000
```

**第 1 次调用就被拦下，0 token 消耗**——这就是「事前检查」的价值。如果是事后记账，这次调用的钱已经花了。

### 3.4 轮次与墙钟护栏

```
=== [4] 自修复轮次上限 ===
  第 1 轮通过，剩余可用轮次 = 0
  ✓ 第 2 轮被拦下：自修复轮次达到上限 1（QUOTA_EXCEEDED）
```

### 3.5 并发安全：check-then-act 竞态

```
=== [5] 并发安全：check-then-act 竞态 ===
  如果先 if (used + n <= max) 再 used += n，两个线程会同时通过检查。
  本类的 acquire* 与 settle* 全部 synchronized，保证检查与扣减原子。
```

```java
// ✗ 非原子：两个线程可以同时通过检查
if (used + n <= max) {
    used += n;
}

// ✓ 原子：检查与扣减在一个 synchronized 方法里
public synchronized void acquireModelCall(int estimatedTokens) { ... }
```

多租户并发时，非原子的预算检查会导致**实际消耗远超上限**——而这个 bug 只在并发下出现，极难复现。

### 3.6 成本换算

```
=== [6] 成本换算 ===
    1,000 token → ¥0.030
   10,000 token → ¥0.300
   20,000 token → ¥0.600
  → 一个自修复失控的循环跑 20 轮 × 6 次调用，
    就是这个数字乘以 120。这就是为什么必须有预算护栏。
```

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| 内存 `TaskTrace` | OpenTelemetry Span，导出到 Jaeger / Grafana |
| 硬编码价格 | 按模型、按租户套餐计费（见 DATABASE.md 的计量表） |
| 固定预算 | 按租户套餐动态下发（免费版 5k token，企业版 100k） |
| 单任务护栏 | 加租户级日配额 + 限流 |

三个生产经验：

1. **`bottleneck()` 要进监控大盘**。如果某天瓶颈从「编译」变成「模型调用」，说明模型侧出了问题（超时/降级），这是很好的早期信号。
2. **预算扣减要能退还**。预估 1281 实际用了 800，多扣的 481 应该退回（`settleTokens` 就是干这个的），否则长任务会被自己的预估值饿死。
3. **超预算不能静默成功**。要么抛异常让上层处理，要么返回 `DEGRADED` 状态并明确告知用户——绝不能悄悄放行。

---

## 五、避坑与优化

### 坑 1：事后记账而非事前检查
**后果**：超预算时钱已经花了。
**做法**：`acquire*` 在调用**之前**执行。

### 坑 2：check-then-act 非原子
**后果**：并发下实际消耗远超上限，且极难复现。
**做法**：`acquire*`/`settle*` 全部 `synchronized`。

### 坑 3：失败的步骤不记录
**后果**：只知道「任务失败」，不知道挂在哪个环节。
**做法**：`timed()` 用 try/catch，失败也记录。

### 坑 4：不统计墙钟与步骤耗时的差
**后果**：埋点覆盖不足时你毫不知情。
**做法**：两个都记，差值过大就补埋点。

### 坑 5：预估 token 不退
**后果**：长任务被自己的预估值累积饿死。
**做法**：`settleTokens(actual)` 按实际结算。

### 优化建议
- **Trace 要带 tokens 维度**：Demo 里只有「模型调用」这一步带 token，其他步骤是 0——生产上补丁解析、上下文渲染也应有估算。
- **预算阈值写进 `.env.example`**：让运维能按套餐调整，而不是改代码。

---

## 本章小结

- Trace 每一步自动计时、失败也记录，自动识别**瓶颈步骤**（实测编译占 83%）。
- **墙钟 vs 步骤耗时**的差值反映埋点覆盖度，差太多说明有未埋点的时间。
- 预算护栏三维度：调用次数（QUOTA）、token 总量（BUDGET）、墙钟（BUDGET），且**必须是事前检查**。
- `acquire*`/`settle*` 全部 `synchronized`——check-then-act 竞态在并发下会让预算失效。
- 失控循环的成本是单次 × 120，**这就是必须有硬刹车的原因**。

---

> **下一章预告**：Chapter 20 进入**编排与 REST API**。19 章的能力全部就位，我们要把它们串成一条 12 步流水线，并暴露成一个「租户从 Header 来、需求从 Body 来」的 HTTP 接口。
>
> 👉 输入「继续」进入 Chapter 20。
