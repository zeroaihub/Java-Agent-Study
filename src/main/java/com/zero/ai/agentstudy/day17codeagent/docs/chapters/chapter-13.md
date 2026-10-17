# Chapter 13 · 自修复循环：Observe-Reason-Act 与三重刹车

> 本章目标：把编译/测试错误喂回模型让它自己修，并装上**三重刹车**：轮次上限、无进展检测、内容指纹比对。理解为什么「先检查一遍是否已经通过」这一步不能省。

---

## 一、为什么要学（Why）

自修复是 Code Agent 最有价值的 capability，也是**最危险的**。

价值很直观：实测同一个任务跑 5 次，`Pass@1 = 80%`，`Pass@2 = 100%`——**多给一轮就从 80% 到 100%**。

危险也很直观：一个没有刹车的循环能烧掉一整晚的配额。三种真实的失控方式：

| 失控方式 | 表现 | 损失 |
| --- | --- | --- |
| **模型没听懂反馈** | 每轮给出**完全相同的错误** | 烧到轮次上限，一无所获 |
| **补丁根本没生效** | 每轮打完补丁，文件内容**一点没变** | 同上，且更难发现（看起来在忙） |
| **错误越修越多** | 每轮引入新错误 | 3 → 4 → 5，越修越糟 |

这三种都不能靠「减少轮次」解决——因为它们发生在**第一轮就该停**的场景。必须专门检测。

---

## 二、是什么（What）

### 2.1 循环结构（Observe-Reason-Act）

```
[0] 先编译一次 ──通过──▶ ALREADY_VALID（0 轮，直接返回）
       │ 失败
       ▼
   for round = 1..maxAttempts:
     [Observe] 收集结构化错误 → feedbackForModel()
     [Reason]  模型生成修复补丁
               ├─ 补丁为空 → NO_FIX_GENERATED（模型放弃了）
               └─ 应用补丁
     [Act]     重新编译
               ├─ 通过 → CONVERGED
               ├─ 刹车 3：内容没变 → NO_PROGRESS
               ├─ 刹车 2：错误签名相同 → NO_PROGRESS
               └─ 继续下一轮
   ── 循环结束 ──▶ ATTEMPTS_EXHAUSTED
```

### 2.2 五种停止原因

| 原因 | 含义 | 是否成功 |
| --- | --- | --- |
| `ALREADY_VALID` | 初始就通过，0 轮 | ✓ |
| `CONVERGED` | 第 N 轮修复成功 | ✓ |
| `NO_PROGRESS` | 无进展（内容没变 / 错误相同） | ✗ |
| `NO_FIX_GENERATED` | 模型放弃了，没给补丁 | ✗ |
| `ATTEMPTS_EXHAUSTED` | 轮次用尽 | ✗ |

**区分停止原因是可观测性的关键**。线上看到 `NO_PROGRESS` 占多数，说明提示词有问题（模型没听懂反馈）；看到 `ATTEMPTS_EXHAUSTED` 占多数，说明轮次给少了或任务确实太难。笼统报「修复失败」等于什么都没说。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.iterate.Chapter13Demo`

### 3.1 仓库本来就是好的：先检查一遍

```
=== [1] 仓库本来就是好的 ===
  RepairOutcome{成功=true, 轮次=0, 停止原因=ALREADY_VALID, 539ms} 初始状态即编译通过，无需修复
  → 一行都没重编译，直接返回。这是很多实现会漏掉的一步：
    明明已经通过了还要跑修复，白烧一次模型调用。
```

```java
CompileVerifier.CompilationResult current = verifier.compile(vfs, outputDir);
if (current.success()) {
    return new RepairOutcome(true, 0, List.of(), StopReason.ALREADY_VALID, ...);
}
```

**这一步最容易被漏掉**。很多实现进来就 `for (round...) { 生成 → 应用 → 编译 }`，于是「本来就好的仓库」也要白白跑一轮模型调用。而实测里**大部分任务第一轮就成功**——这一句能省下相当比例的成本。

### 3.2 缺一个分号：一轮修好

```
=== [2] 缺一个分号：一轮修好 ===
    [模型收到反馈] 编译未通过，请修正以下错误后重新输出补丁： - 错误 src/main/java/demo/order/Money.java 第 29 行：';' expected
  RepairOutcome{成功=true, 轮次=1, 停止原因=CONVERGED, 174ms} 第 1 轮修复成功
    第 1 轮：1 个补丁, 错误 1 → 0, 编译通过, 127ms
```

这就是自修复的价值所在：模型拿到「第 29 行缺分号」，给出补丁，编译通过，**1 轮结束**。

### 3.3 刹车 2：模型没听懂反馈

```
=== [3] 模型没听懂反馈：无进展检测 ===
  RepairOutcome{成功=false, 轮次=1, 停止原因=NO_PROGRESS, 108ms} 连续两轮产生完全相同的错误集合，继续重试没有意义
    第 1 轮：1 个补丁, 错误 1 → 1, 编译失败, 77ms
  → 没有开启 stopOnNoProgress 时会发生什么？
  关闭检测：轮次=5 停止原因=ATTEMPTS_EXHAUSTED | 开启检测：轮次=1 停止原因=NO_PROGRESS
```

**这是本章最重要的一组对照数据**：

| 配置 | 轮次 | 停止原因 | 成本 |
| --- | --- | --- | --- |
| 关闭无进展检测 | 5 | `ATTEMPTS_EXHAUSTED` | 5 次模型调用 + 5 次编译 |
| 开启无进展检测 | **1** | `NO_PROGRESS` | 1 次模型调用 + 2 次编译 |

**省了 80% 的成本，结论完全一样（都是失败）**。而且 `NO_PROGRESS` 这个原因本身就告诉你「模型没听懂反馈，该改提示词了」，而 `ATTEMPTS_EXHAUSTED` 什么都没告诉你。

实现：

```java
String nowSignatures = String.join("|", current.errorSignatures());
if (stopOnNoProgress && Objects.equals(previousSignatures, nowSignatures)) {
    return new RepairOutcome(false, round, ...,
            StopReason.NO_PROGRESS, ..., "连续两轮产生完全相同的错误集合，继续重试没有意义");
}
```

用的正是 Ch11 的 `errorSignatures()`。

### 3.4 刹车 3：内容指纹比对

```java
RepoSnapshot beforeSnapshot = vfs.snapshot();
... 应用补丁 ...
RepoSnapshot afterSnapshot = vfs.snapshot();

// 刹车 3：内容没变 = 补丁根本没生效
boolean noop = beforeSnapshot.sameAs(afterSnapshot);
if (noop && stopOnNoProgress) {
    return new RepairOutcome(false, round, ...,
            StopReason.NO_PROGRESS, ...,
            "补丁应用后仓库内容没有任何变化（请检查 SEARCH 片段是否来自当前文件内容）");
}
```

用的正是 Ch03 的内容指纹。它抓住的场景是：**补丁「应用成功」了，但什么都没改**（比如 SEARCH 和 REPLACE 内容相同）。

> 注意：`PatchApplier`（Ch07）已经保证「命中 0 处必须失败」，但**命中了 1 处且替换后内容不变**这种情况仍然存在（SEARCH == REPLACE）。所以指纹比对是最后一道网。

### 3.5 刹车 1：轮次用尽

```
=== [4] 错误多到一轮修不完：轮次用尽 ===
  RepairOutcome{成功=false, 轮次=2, 停止原因=ATTEMPTS_EXHAUSTED, 111ms} 已达最大重试轮次 2，仍未通过编译
    第 1 轮：1 个补丁, 错误 3 → 4, 编译失败, 52ms
    第 2 轮：1 个补丁, 错误 4 → 5, 编译失败, 41ms
  最后剩下的错误：
      [错误] src/main/java/demo/order/Broken.java:4 illegal start of expression
      ... （5 条）
```

注意 `错误 3 → 4 → 5`：**错误在变多**。这种情况下错误签名每轮都不同，所以无进展检测不会触发，只能靠轮次上限兜底——这正是刹车 1 存在的意义。

> Demo 里刻意构造了一个「每轮给 `Broken.java` 追加一行 `int attemptN = ;`（引入新错误）」的生成器。最初版本改的是 README（不影响错误集合），结果被无进展检测判为 `NO_PROGRESS` 而不是 `ATTEMPTS_EXHAUSTED`——这个对照也说明了**两个刹车检测的是不同的东西**。

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| `maxAttempts=3` | 按任务难度动态：小改动 2 轮，重构 5 轮 |
| 固定 `stopOnNoProgress=true` | 保持一致，但记录触发次数做监控 |
| 只修编译错误 | 加上测试失败反馈（Ch12 的断言描述） |
| 纯模型修 | **规则修 + 模型修混合**：缺分号、缺 import 这类用规则修更快更准 |

两个生产经验：

1. **`maxAttempts` 应该由预算护栏动态决定**（Ch19），而不是写死。剩余预算不够就少给几轮。
2. **“错误变多”要单独告警**。`3 → 4 → 5` 说明模型在乱改，应该立刻转人工，而不是继续。

---

## 五、避坑与优化

### 坑 1：进来就循环，不先检查一次
**后果**：本来就好的仓库白烧一轮模型调用。而实测大部分任务第一轮就成功。
**做法**：先编译一次，`ALREADY_VALID` 直接返回。

### 坑 2：没有无进展检测
**后果**：同样失败，多烧 4 轮（80% 成本），且报 `ATTEMPTS_EXHAUSTED` 掩盖了真实原因。
**做法**：比较错误签名集合（Ch11）+ 内容指纹（Ch03）。

### 坑 3：把「补丁应用成功」当成「修好了」
**后果**：SEARCH == REPLACE 时内容没变却汇报成功，循环空转。
**做法**：快照指纹比对。

### 坑 4：不区分停止原因
**后果**：线上只知道「修复失败」，不知道该调提示词还是加轮次。
**做法**：五种 `StopReason` 全部记录到 Trace。

### 坑 5：无脑增加轮次
**后果**：实测 `Pass@2 = 100%` 后，第 3 轮之后的边际收益几乎为零，纯烧钱。
**做法**：用 Pass@k 数据（Ch18）反推合理轮次——**本课程设 3 轮的依据就在这里**。

### 优化建议
- **规则修优先**：缺分号、缺 import、多余括号这类错误，用确定性的规则修补比问模型快 10 倍且 100% 准确。
- **每轮重新读取文件当前内容**：避免复用失效的 `occurrence`（Ch07 坑 4）。

---

## 本章小结

- 自修复的价值：实测 `Pass@1=80%` → `Pass@2=100%`，一轮就有 20 个百分点的提升。
- 循环结构 Observe-Reason-Act，**第一步必须先编译一次**（`ALREADY_VALID` 直接返回）。
- **三重刹车**：轮次上限（兜底）、错误签名相同（`NO_PROGRESS`）、内容指纹没变（`NO_PROGRESS`）。
- 无进展检测实测**省 80% 成本且结论相同**；它还把失败原因从「轮次用尽」精确到「模型没听懂反馈」。
- 五种 `StopReason` 必须区分记录——它决定了线上该调提示词还是加轮次。
- 轮次上限应由预算护栏动态决定，且用 Pass@k 数据反推合理值。

---

> **下一章预告**：Chapter 14 进入**安全护栏**。自修复能救回错误的补丁，但有一类补丁**根本不该被打进去**——比如改 CI 配置、往代码里塞密钥。我们要在落盘之前就把它们拦下来。
>
> 👉 输入「继续」进入 Chapter 14。
