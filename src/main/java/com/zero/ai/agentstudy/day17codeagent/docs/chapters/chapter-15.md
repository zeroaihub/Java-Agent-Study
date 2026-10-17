# Chapter 15 · 规则式代码评审：只评审增量

> 本章目标：建立「编译能过、测试也能过，但就是不该进生产」的质量规则集，理解为什么**只评审 ADD 行**，以及为什么「确定性问题不交给 LLM」。

---

## 一、为什么要学（Why）

先看一段真实的代码——它能编译、能过测试，但每一处都不该进生产：

```java
public static long counter = 0;                  // 公开可变静态字段：多线程竞态
System.out.println("checking " + orderId);       // 调试输出残留
Thread.sleep(3000);                              // 魔法数字
} catch (InterruptedException e) {               // 空 catch：吞掉中断异常
// TODO 加入缓存                                  // 遗留待办
... || counter == 100000;                        // 魔法数字
```

**编译通过、测试也可能通过**——但这段代码上线后会：竞态计数不准、日志噪音、线程被无故阻塞、中断信号被吞导致无法优雅停机。

这类问题有三个特点：

1. **确定性**：有没有空 catch，是个事实判断，不需要理解业务；
2. **高频**：每次改动都可能出现；
3. **规则可表达**：一条正则就能抓住。

所以它们**不该交给 LLM**：既贵（每次改动都调一次模型）、又不可靠（模型会漏、会瞎编意见）。规则 100% 准确且零成本。

> 反过来，**需要理解语义的**（命名是否贴合业务、抽象是否合理）才交给 LLM。**两者混为一谈的结果就是既贵又不可靠。**

---

## 二、是什么（What）

### 2.1 规则集

| 规则 | 严重级 | 抓什么 |
| --- | --- | --- |
| 可变静态字段 | **BLOCKER** | `public static` 非 final 字段：多线程竞态温床 |
| 空 catch 块 | **BLOCKER** | 吞掉异常让线上问题彻底失去线索 |
| printStackTrace | MAJOR | 堆栈打到 stderr，日志系统收不到 |
| System.out 残留 | MINOR | 调试输出不该进生产 |
| 魔法数字 | MINOR | 可读数字应抽成有名字的常量 |
| 超长行（>120） | MINOR | 影响 code review 可读性 |
| TODO/FIXME | INFO | 遗留待办，确认是否落任务单 |

### 2.2 严重级

```java
public enum Severity {
    BLOCKER("必须修改"),    // 必须改，否则不给合并
    MAJOR("建议修改"),
    MINOR("可选优化"),
    INFO("提示");

    public boolean isBlocking() { return this == BLOCKER; }
}
```

`CodeTaskResult.mergeable()` 会检查是否存在 BLOCKER——**有 BLOCKER 的 PR 不允许自动合并**（Ch20）。

### 2.3 只评审增量

```java
for (UnifiedDiff.DiffLine line : lines) {
    if (line.type() != UnifiedDiff.DiffLine.Type.REMOVE) {
        newLineNo++;
    }
    if (line.type() != UnifiedDiff.DiffLine.Type.ADD) {
        continue;      // ← 只评审 ADD 行
    }
    ...
}
```

这条规则的理由很实在：**评审全量会把历史遗留问题一次性倾泻给 PR 作者，结果就是没人看**。Demo 里专门验证了「零改动 → 零意见」。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.review.Chapter15Demo`

### 3.1 审查一个「能编译但质量糟糕」的补丁

```
=== [1] 审查一个能通过编译但质量糟糕的补丁 ===
  PatchResult[OK src/main/java/demo/order/OrderService.java 匹配 1 处, 策略=EXACT]
    [必须修改/可变静态字段] OrderService.java:23 公开可变静态字段在多线程下是竞态温床，且任何代码都能改：public static long counter = 0;｜建议：改为 private static final，或提供受控的访问方法
    [可选优化/System.out 残留] OrderService.java:26 调试输出不应进入生产代码：System.out.println("checking " + orderId);｜建议：删除，或改为结构化日志
    [可选优化/魔法数字] OrderService.java:28 可读数字应当抽取为有名字的常量：Thread.sleep(3000);｜建议：提为 private static final 常量并命名
    [必须修改/空 catch 块] OrderService.java:29 吞掉异常会让线上问题彻底失去线索：} catch (InterruptedException e) {｜建议：至少要记录日志：catch (XxxException e) { log.warn("...", e); }
    [提示/TODO/FIXME] OrderService.java:31 遗留待办标记：// TODO 加入缓存｜建议：确认是否需要落成正式任务单
    [可选优化/魔法数字] OrderService.java:32 可读数字应当抽取为有名字的常量：... || counter == 100000;｜建议：提为 private static final 常量并命名

  ReviewSummary{共 6 条，必须修改 2，建议 0，可选 3}
```

每条意见都带：**严重级 / 规则名 / 文件:行号 / 问题描述（含触发行原文） / 修改建议**。

注意行号是**改动后的新行号**（`newLineNo` 只对非 REMOVE 行递增）——这样才能直接定位到 PR 里看到的位置。

### 3.2 为什么只评审增量

```
=== [2] 为什么只评审增量 ===
  没有任何改动时产生的意见数 = 0
  → 评审全量会把历史遗留问题一次性倾泻给 PR 作者，结果就是没人看
```

### 3.3 干净的补丁应当零意见

```
=== [3] 干净的补丁应当零意见 ===
  意见条数 = 0（0 才是对的，否则说明规则误报太高）
```

**这条和第 1 条同样重要**。误报率高的评审器会被团队静音——而一个被静音的评审器还不如没有。

### 3.4 两个真实修掉的规则 bug

本节在实现时踩了两个坑，都很有代表性：

**坑 A：空 catch 正则不匹配真实代码**

```java
// ✗ 只匹配单行空 catch，而真实代码几乎都是多行的
Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}")

// ✓ 补上行尾情形：catch (...) { 后直接换行
Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}|catch\\s*\\([^)]*\\)\\s*\\{\\s*$")
```

**坑 B：TODO 规则永不触发**

因为注释行被跳过了。解决方案是给规则加一个 `appliesToComments` 标志：

```java
rules.put("TODO/FIXME", new PatternRule(
        Pattern.compile("TODO|FIXME|XXX"),
        ReviewComment.Severity.INFO,
        "遗留待办标记",
        "确认是否需要落成正式任务单",
        true));   // ← appliesToComments：这条规则专门看注释

// 循环里：
boolean isComment = trimmed.startsWith("//") || trimmed.startsWith("*");
if (trimmed.isEmpty() || (isComment && rules.values().stream()
        .noneMatch(r -> r.appliesToComments()))) {
    continue;   // 没有任何规则关心注释时，才跳过注释行
}
for (...) {
    if (isComment && !rule.appliesToComments()) {
        continue;   // 注释行只跑显式声明过的规则，避免误报
    }
    ...
}
```

**这个设计的价值**：注释默认被跳过（避免把注释里的代码示例当成真实代码报一堆误报），但显式声明「我就是要看注释」的规则（如 TODO）仍能生效。

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| 7 条正则规则 | Checkstyle / SpotBugs / SonarQube（数百条成熟规则） |
| 纯规则 | **规则 + LLM 混合**：规则管确定性，LLM 管语义 |
| 意见只打印 | 意见写进 PR 评论（Ch17 的「需要关注」区块） |
| 固定规则集 | 规则可按仓库 / 团队配置开关 |

一个重要的分工原则：

| 交给规则 | 交给 LLM |
| --- | --- |
| 格式、命名规范 | 命名是否贴合业务语义 |
| 已知反模式（空 catch、魔法数字） | 抽象层次是否合理 |
| 安全红线（Ch14） | 是否引入了重复逻辑 |
| 性能反模式 | 边界条件是否考虑周全 |

**规则负责「确定性」，LLM 负责「需要理解语义」**。混在一起既贵又不可靠。

---

## 五、避坑与优化

### 坑 1：评审全量代码
**后果**：历史遗留问题一次性倾泻，PR 作者直接忽略全部意见。
**做法**：只评审 diff 的 ADD 行。

### 坑 2：把确定性问题交给 LLM
**后果**：每次改动都调一次模型（贵），且模型会漏报、会瞎编（不可靠）。
**做法**：正则规则零成本 100% 准确，先用规则。

### 坑 3：注释行处理一刀切
**后果**：要么 TODO 规则永不触发，要么把注释里的代码示例当成真实代码误报一堆。
**做法**：`appliesToComments` 标志，默认跳过注释，显式声明的规则例外。

### 坑 4：正则不匹配真实代码形态
**后果**：规则写了但从不触发，你以为有防护其实没有。
**做法**：**用真实代码验证每条规则**（Demo 的 `[1]` 就是干这个的）。

### 坑 5：不监控误报率
**后果**：误报太多 → 团队忽略评审 → 评审器形同虚设。
**做法**：统计「被 WARN 但人工判定无问题」的比例，超阈值就调规则。

### 优化建议
- **行号要映射到改动后**：否则 PR 里点开的位置对不上。
- **规则要可导出**：Demo 的 `[0]` 打印了规则清单，它同时也是给使用者的文档。

---

## 本章小结

- 有一类问题**编译能过、测试也能过，但就是不该进生产**——需要第三道验证：评审。
- 七条规则，两级 BLOCKER（可变静态字段、空 catch）会阻止自动合并。
- **只评审 ADD 行**：评审全量会把历史问题倾泻给作者，结果就是没人看。
- **确定性问题交规则，语义问题交 LLM**——混在一起既贵又不可靠。
- 注释行用 `appliesToComments` 标志精细控制：默认跳过，TODO 这类规则例外。
- 规则必须用真实代码验证（否则「写了但从不触发」），并监控误报率。

---

> **下一章预告**：Chapter 16 进入**测试生成**。我们已经能验证 Agent 的改动了，但如果仓库本来就没有测试呢？我们要让 Agent 自己写测试——但必须让它成为一道可控的工序，而不是「顺便写点」。
>
> 👉 输入「继续」进入 Chapter 16。
