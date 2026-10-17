# Chapter 07 · 补丁表示与应用：打不上就必须明确失败

> 本章目标：掌握 SEARCH/REPLACE 补丁模型与四级匹配策略，理解本课**最重要的一条铁律**——补丁打不上时必须明确失败，绝不能「聪明地」近似替换。

---

## 一、为什么要学（Why）

模型要改动代码，有两种输出方式：

| 方式 | 描述 | 问题 |
| --- | --- | --- |
| **整文件重写** | 输出修改后的完整文件 | ① token 贵；② **模型会顺手改掉无关代码**（缩进、注释、它「觉得不好」的地方）；③ diff 无法审阅 |
| **SEARCH/REPLACE 补丁** | 只输出「把这段替换成那段」 | 可能打不上（模型凭记忆写了旧代码） |

整文件重写的第 ② 点是致命的：你让模型「给 Money 加个乘法」，它返回的文件里可能顺手把 `private` 改成了 `public`、把一段注释删了。这种改动**混在几十行 diff 里极难发现**，而它带来的风险是真实的。

所以本课程选 SEARCH/REPLACE。但它带来一个新问题：**补丁会打不上**。

而这里藏着本课程最严重的一个陷阱：

> 如果补丁打不上时，实现「聪明地」做近似替换（比如模糊匹配一下塞进去），代码会被**静默改坏**——而且因为「没报错」，你要到很久以后才发现。**静默的错误比明确的失败危险一万倍。**

---

## 二、是什么（What）

### 2.1 补丁模型

```java
public record TextPatch(
        String filePath,
        String search,      // 要被替换的原文（必须来自文件当前内容）
        String replace,     // 替换后的内容
        Integer occurrence, // null=必须唯一；-1=全部；n=第 n 处
        String note         // 说明
) { }
```

`occurrence` 的语义是本模块的核心约定：

| 值 | 含义 | 失败条件 |
| --- | --- | --- |
| `null` | **必须唯一** | 命中 ≠ 1 → `PATCH_AMBIGUOUS` |
| `-1` | 全部替换 | 命中 0 → 失败 |
| `n` | 第 n 处 | 命中 < n → 失败 |

**默认必须唯一**是关键设计：模型如果不确定，就应该失败并重新读取文件，而不是「猜一个」。

### 2.2 四级匹配策略

| 级别 | 策略 | 放宽程度 |
| --- | --- | --- |
| 1 | `EXACT` | 完全一致 |
| 2 | `IGNORE_TRAILING_WHITESPACE` | 忽略行尾空白 |
| 3 | `IGNORE_INDENT` | 忽略缩进 |
| 4 | `NORMALIZE_WHITESPACE` | 归一化所有空白 |

后三级只在**容错模式**（`PatchApplier.tolerant()`）下启用。默认 `strict()` 只做精确匹配。

即使用了容错，`PatchResult.usedFuzzyMatch()` 会被标记为 `true`——**评审环节应当据此要求人工复核**。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.patch.Chapter07Demo`

### 3.1 精确匹配成功

```
=== [1] 精确匹配：给 Money 增加 multiply 方法 ===
  PatchResult[OK src/main/java/demo/order/Money.java 匹配 1 处, 策略=EXACT]
    替换后片段：
          public Money multiply(long factor) {
              return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
```

### 3.2 上下文不匹配：明确失败（**最重要的一节**）

模型凭记忆写了个补丁，`search` 片段是它印象里的旧代码：

```
=== [2] 上下文不匹配（模型凭记忆写的补丁）===
  PatchResult[FAIL src/main/java/demo/order/Money.java 补丁未能应用：未匹配到 search 片段（精确匹配命中 0 处） | 建议：请重新读取文件当前内容，确认片段首行「public Money add(Money other) {」是否存在、缩进与空行是否一致；不要用记忆里的旧代码写补丁]
  → 若这里「聪明地」退化成近似替换，代码会被静默改坏且很难发现
```

注意失败信息里带了两样东西：**命中数（0 处）** 和 **可执行建议（重新读取文件当前内容，确认首行）**。这份建议会直接喂回模型，让它下一轮改正（Ch13）。

**实现上，这里曾经有一个真实的 bug**（本课程刻意保留了这个教训）：

```java
// ✗ 曾经的写法：replaceNth 找不到时原样返回 original，却被判定为成功
return PatchResult.ok(patch.filePath(),
        replaceNth(original, patch.search(), patch.replace(), nth), exactCount, EXACT);

// ✓ 现在的写法：命中数为 0 时明确失败
if (exactCount > 0) {
    return tryMatch(original, patch, exactCount);
}
if (!allowFuzzy) {
    return explainFailure(patch, exactCount);
}
```

以及最后一道兜底防线：

```java
String newContent = applyFuzzy(original, patch, strategy);
if (newContent.equals(original)) {
    // 兜底防线：任何一级都不允许「内容没变却汇报成功」
    return PatchResult.fail(patch.filePath(),
            "补丁未能应用：归一化后命中 " + fuzzy.count() + " 处，但内容未发生任何变化",
            "请检查 replace 片段是否与 search 片段实质不同", fuzzy.count());
}
```

### 3.3 多处命中：宁可失败也不猜

```
=== [3] 多处命中：目标不唯一 ===
  PatchResult[FAIL src/main/java/demo/order/Money.java 补丁目标不唯一：匹配到 3 处，而补丁未指定 occurrence | 建议：请在 search 片段里多带几行上下文使其唯一，或使用 occurrence 指定第几处]
```

`return build(BigDecimal.ZERO);` 在 `Money.java` 里出现了 3 次。默认 `occurrence=null` 要求唯一，所以直接失败。

**为什么不能默认改第一处？** 因为「第一处」几乎肯定不是模型想要的那一处。让它失败、让它多带几行上下文，是更安全的收敛路径。

### 3.4 occurrence 与 replaceAll

```
=== [4] 指定 occurrence=2：只改第二处 ===
  PatchResult[OK src/main/java/demo/order/Money.java 匹配 3 处, 策略=EXACT]

=== [5] replaceAll：全部替换 ===
  PatchResult[OK src/main/java/demo/order/Money.java 匹配 3 处, 策略=EXACT]
    替换后片段：
              return Money.from(BigDecimal.valueOf(cents));
              return Money.from(BigDecimal.ZERO);
              return Money.from(this.amount.add(other.amount));

=== [6] occurrence 越界 ===
  PatchResult[FAIL src/main/java/demo/order/Money.java occurrence=99 超出实际命中数 3 | 建议：请下调 occurrence 或检查文件是否已被上一步修改]
```

`occurrence=99` 的失败信息里有一句很实用的提示：「或检查文件是否已被上一步修改」——这是自修复循环里最常见的情形：**上一轮的补丁已经把文件改了，这一轮的 occurrence 就失效了**。

### 3.5 容错模式：模型丢了缩进

```
=== [7] 容错模式：模型丢掉了缩进 ===
  PatchResult[OK src/main/java/demo/order/Money.java 匹配 1 处, 策略=IGNORE_INDENT]
  是否用了有损容错 = true（为 true 时评审环节应要求人工复核）
```

容错确实救回了这次补丁，但 **`usedFuzzyMatch=true` 必须被记录并传递到评审环节**。容错是「给你一次机会」，不是「替你兜底」。

另外注意：容错放宽后如果变成**多处命中**，依然要失败：

```java
if (fuzzy.count() > 1 && !patch.replaceAll()) {
    // 一旦放宽后变成多处命中，宁可失败也不猜
    return PatchResult.fail(..., "补丁目标不唯一：在放宽空白后匹配到 " + fuzzy.count() + " 处", ...);
}
```

### 3.6 落盘并生成审阅用 diff

```
=== [8] 落到 VFS 并生成审阅用 diff ===
--- a/src/main/java/demo/order/Money.java
+++ b/src/main/java/demo/order/Money.java
@@ -6 +10 @@
         return new Money(this.amount.add(other.amount));
     }
 
+    public Money multiply(long factor) {
+        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
+    }
+
     public boolean greaterThan(Money other) {
         return this.amount.compareTo(other.amount) > 0;
     }

  统计 = {added=4, removed=0, changed=4}
```

`+4 / -0` 这个统计会一路传到 Ch17 的变更集与风险分级。

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| `TextPatch`（SEARCH/REPLACE） | 同样模型，或 Unified Diff（Ch08 已支持解析） |
| `PatchApplier` | 可换 `git apply`，但需保留「打不上就明确失败」语义 |
| 内存 diff | `UnifiedDiff` 输出给 PR；大仓库用 `git diff` |

一个生产经验：**`occurrence` 默认必须唯一**这条规则，在自修复循环里会产生「上一轮改了文件 → 这一轮 occurrence 失效」的连环失败。解决办法是：**每轮修复前重新读取文件当前内容**，让模型基于最新内容生成补丁，而不是让它在旧补丁上改 occurrence。

---

## 五、避坑与优化

### 坑 1：补丁打不上时静默成功（**最严重**）
**后果**：代码被静默改坏，很久以后才发现，且无法追溯是哪一步坏的。
**做法**：命中 0 处必须 `explainFailure`；模糊匹配后必须校验 `newContent != original`。

### 坑 2：多处命中时默认改第一处
**后果**：改错位置，且「看起来成功了」。
**做法**：`occurrence=null` 时要求唯一，否则 `PATCH_AMBIGUOUS`。

### 坑 3：容错成功却不标记
**后果**：有损替换被当成精确替换，评审放行了本该人工复核的改动。
**做法**：`usedFuzzyMatch=true` 必须传递到评审与 PR。

### 坑 4：自修复里复用旧 occurrence
**后果**：上一轮改完文件后，这一轮的 occurrence 指向了错的位置。
**做法**：每轮修复前重新读取文件当前内容。

### 坑 5：`Integer occurrence` 拆箱 NPE
**后果**：`occurrence=null` 时按 `int` 使用直接 NPE。
**做法**：`int nth = patch.replaceAll() ? -1 : (patch.occurrence() == null ? 1 : patch.occurrence());`

### 优化建议
- **失败信息带上「首行」**：「确认片段首行『xxx』是否存在」比「未匹配」有用得多。
- **diff 用 compact 模式**（上下文 ±3 行）：给模型/人审阅时省 token。

---

## 本章小结

- 用 SEARCH/REPLACE 而非整文件重写：省 token、防止顺手改无关代码、diff 可审阅。
- `occurrence=null` 必须唯一、`-1` 全部、`n` 第 n 处；越界与歧义都失败。
- **打不上必须明确失败**，命中 0 处走 `explainFailure`，模糊后校验内容确实变了——**静默的错误比明确的失败危险一万倍**。
- 四级匹配策略（EXACT → 忽略行尾空白 → 忽略缩进 → 归一化空白），后三级仅容错模式启用，且必须标记 `usedFuzzyMatch`。
- 失败信息要带命中数与可执行建议，这份建议是自修复循环的输入。

---

> **下一章预告**：Chapter 08 进入**补丁解析**。模型不会乖乖只输出补丁——它会先聊两句、用反引号包住路径、偶尔改用 Unified Diff 格式。我们要从这些「脏输出」里把结构化补丁救出来。
>
> 👉 输入「继续」进入 Chapter 08。
