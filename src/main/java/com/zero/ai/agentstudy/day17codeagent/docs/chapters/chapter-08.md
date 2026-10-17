# Chapter 08 · 从 LLM 输出解析补丁：把结构化数据从脏文本里救出来

> 本章目标：掌握从「含闲聊、反引号、格式漂移」的模型输出中稳健解析出补丁的方法，理解为什么**解析不出补丁必须明确失败**而不是「尽力猜一个」。

---

## 一、为什么要学（Why）

Chapter 07 假设补丁已经是结构化的 `TextPatch`。但真实世界里，模型不会乖乖只输出补丁。它会这样：

```
好的，我来修改 multiply：

File: `src/main/java/demo/order/Money.java`
<<<<<<< SEARCH
    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }
=======
    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money multiply(long factor) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
    }
>>>>>>> REPLACE
```

注意三处「脏」：开头有闲聊、`File:` 后的路径被反引号包住、代码块没有语言标注。

更麻烦的是**格式漂移**：同一个模型，这次输出 SEARCH/REPLACE，下次可能输出 Unified Diff（`--- a/...` / `+++ b/...` / `@@`）。如果你只支持一种格式，就会有相当比例的输出被判为「解析失败」——而实际上补丁是完全正确的。

所以解析器必须：

1. **容忍闲聊**：开头结尾的聊天内容直接忽略；
2. **容忍装饰**：反引号、`File:` 前缀、有无空格都接受；
3. **支持多种格式**：SEARCH/REPLACE 与 Unified Diff 都要能解析；
4. **明确报告被拒绝的片段**：哪些块看起来像补丁但缺了分隔符，要说清楚；
5. **一个都解析不出来时明确失败**：`PATCH_PARSE_FAILED`。

---

## 二、是什么（What）

### 2.1 两种输入格式

| 格式 | 特征 | 优点 |
| --- | --- | --- |
| **SEARCH/REPLACE** | `<<<<<<< SEARCH` / `=======` / `>>>>>>> REPLACE` | 模型天然容易产出；上下文明确 |
| **Unified Diff** | `--- a/path` / `+++ b/path` / `@@ -6,3 +6,7 @@` / `+`/`-`/空格前缀 | git 原生格式；人熟悉 |

两种都要支持，因为它们在不同模型、不同提示词下各有优势。

### 2.2 解析结果

```java
public record ParsedPatches(
        List<TextPatch> patches,     // 成功解析的补丁
        List<String> rejected        // 看起来像补丁但被判无效的片段（附理由）
) { }
```

`rejected` 这个字段是刻意的：**「解析失败」和「部分解析失败」是完全不同的事**。如果模型给了 2 个补丁、第 2 个缺了分隔符，你应该知道「第 2 个有问题」，而不是笼统地看到「解析失败」。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.patch.Chapter08Demo`

### 3.1 从含闲聊 / 反引号的脏输出里解析

```
=== [1] 从含闲聊 / 反引号的脏输出里解析 ===
  解析出 2 个补丁：
    TextPatch{src/main/java/demo/order/Money.java, +7 -3}
    TextPatch{src/main/java/demo/order/OrderService.java, +9 -5}
  被忽略的片段数 = 0（模型开头的闲聊，正常）
```

闲聊被忽略、反引号被剥掉、两个补丁都被救出来了。

### 3.2 应用并生成审阅 diff

```
=== [2] 逐个应用到真实文件 ===
  PatchResult[OK src/main/java/demo/order/Money.java 匹配 1 处, 策略=EXACT]
  PatchResult[OK src/main/java/demo/order/OrderService.java 匹配 1 处, 策略=EXACT]
  成功 2/2
```

两个补丁**都精确匹配**——说明解析出的 `search` 片段确实来自文件当前内容，解析过程没有引入偏差。

### 3.3 Unified Diff 也能解析

```
=== [4] Unified Diff 格式也能解析 ===
  路径 = src/main/java/demo/order/DiscountPolicy.java
  SEARCH =
          private static final long VIP_THRESHOLD = 100_00L;
      
          public double discountFor(long amountCents, boolean vip) {
              if (!vip) {
                  return 1.0;
              }
              if (amountCents >= VIP_THRESHOLD) {
                  return 0.8;
              }
              return 0.95;
          }

  REPLACE = ...
  应用 → PatchResult[OK src/main/java/demo/order/DiscountPolicy.java 匹配 1 处, 策略=EXACT]
```

解析器把 Unified Diff 的 hunk 还原成了 SEARCH/REPLACE，再交给同一个 `PatchApplier`——**复用 Ch07 的全部匹配逻辑**，不需要为第二种格式重写一套应用逻辑。

这里有个**真实踩到并修掉的坑**。Unified Diff 里的「上下文行」本应是一个空格前缀 `" "`，但大量编辑器、git 工具、聊天窗口会**剥掉行尾空白**，把它变成真正的空行 `""`。如果按「空行不是有效行」丢弃，SEARCH 片段就会少一个空行，补丁必然打不上：

```java
// 关键容错：diff 里的「空上下文行」本应是单个空格 " "，
// 但大量编辑器 / git 工具 / 聊天窗口会剥掉行尾空白，把它变成真正的空行。
// 若在这里把它丢弃，SEARCH 片段就会缺少一个空行，补丁必然打不上。
if (line.isEmpty()) {
    search.append('\n');
    replace.append('\n');
    continue;
}
```

### 3.4 模型只是聊了聊：明确失败

```
=== [5] 模型只是聊了聊，没有给补丁 ===
  ✓ 明确失败：未能从模型输出中解析出任何补丁。输出尾部：好的，我建议你在 OrderService 里加一个 pay 方法即可。
```

这个场景非常重要。模型**没有骗你**，它只是没按格式输出。此时：

- ✓ 正确做法：明确失败，并把**输出尾部**带回给调用方（人可以立刻看出「它只是在聊天」）；
- ✗ 错误做法：硬造一个补丁、或者把整段输出当文件内容写入。

注意失败信息里带了「输出尾部」——这让排查从「猜模型想干什么」变成「直接看它说了什么」。

### 3.5 部分解析失败：要说清楚哪块有问题

```
=== [6] SEARCH 块缺少 ======= 分隔符 ===
  成功解析 = 1 个
  被判为无效 = [SEARCH 块缺少 ======= 分隔符：src/main/java/demo/order/Money.java]
```

1 个成功、1 个被拒，且被拒的**理由具体到文件名和缺失的分隔符**。这比「解析失败」有用得多——模型下一轮就知道该补上 `=======`。

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| 正则解析两种格式 | 可加 JSON 模式（让模型输出结构化 JSON，最稳但最费 token） |
| 解析失败即 `PATCH_PARSE_FAILED` | 失败时可降级为「再问一次，并在提示词里贴一个正确格式示例」 |
| `rejected` 列表 | 打印到 Trace，用于统计「哪种格式漂移最常发生」 |

一个非常实用的生产经验：**统计被拒片段的理由分布**。如果你发现「缺少 `=======` 分隔符」占了 30%，就该在提示词里加一个更醒目的格式示例；如果「Unified Diff」占了 40%，就干脆把主格式改成 Unified Diff。**解析器的被拒统计，是优化提示词的数据来源。**

---

## 五、避坑与优化

### 坑 1：只支持一种补丁格式
**后果**：模型格式漂移时大量输出被误判为失败。
**做法**：SEARCH/REPLACE + Unified Diff 双支持（复用同一个 `PatchApplier`）。

### 坑 2：丢弃 diff 里的空上下文行
**后果**：SEARCH 片段少一个空行，补丁打不上，且极难排查（看起来一模一样）。
**做法**：`line.isEmpty()` 按上下文行处理，补 `'\n'` 到 search 与 replace。

### 坑 3：解析不出补丁时硬凑
**后果**：把闲聊文本写进源文件，直接毁掉代码。
**做法**：明确 `PATCH_PARSE_FAILED`，并把输出尾部带回给调用方。

### 坑 4：不区分「全失败」与「部分失败」
**后果**：2 个补丁里 1 个坏了，你只知道「失败」，不知道该修哪个。
**做法**：`rejected` 列表带上文件名与具体理由。

### 坑 5：路径不做归一化
**后果**：反引号、`./` 前缀、`a/` 前缀导致路径对不上。
**做法**：`normalizePath()` 统一剥离装饰与 `a/`、`b/` 前缀。

### 优化建议
- **提示词里给格式示例**：比反复在解析器里加容错便宜得多。
- **把 `rejected` 记入 Trace**：它是提示词优化的宝贵数据。

---

## 本章小结

- 模型输出必然是脏的：闲聊、反引号、格式漂移都是常态，解析器必须容忍。
- 同时支持 SEARCH/REPLACE 与 Unified Diff，**复用同一个 `PatchApplier`**。
- **diff 空上下文行必须按上下文处理**——这是真实踩到并修掉的坑。
- 解析不出补丁 → 明确 `PATCH_PARSE_FAILED` 并带回输出尾部；绝不硬凑。
- 区分「全失败」与「部分失败」：`rejected` 带文件名与理由，同时也是提示词优化的数据来源。

---

> **下一章预告**：Chapter 09 进入**代码生成**。补丁怎么应用、怎么解析都清楚了，现在是时候真正调用模型了——我们会搭建模型端口与提示词装配器，并用真实数据量化「整文件重写 vs 补丁」的成本差距。
>
> 👉 输入「继续」进入 Chapter 09。
