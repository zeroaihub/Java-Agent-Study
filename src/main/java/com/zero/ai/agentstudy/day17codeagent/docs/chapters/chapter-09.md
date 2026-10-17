# Chapter 09 · 代码生成：模型端口、提示词装配与成本对比

> 本章目标：搭建「唯一知道 LLM 存在」的 `generator` 模块，掌握提示词中的输出契约写法，并用真实数据量化「整文件重写 vs SEARCH/REPLACE 补丁」的成本差距。

---

## 一、为什么要学（Why）

到 Chapter 08 为止，我们已经能把补丁打进文件了。现在终于要调用模型了。这里有三个容易被忽略的设计点：

**第一，模型调用必须被封装成端口。**
如果 `orchestrator` 直接写 `chatClient.call(prompt)`，那么换模型要改编排层、做评测要改编排层、离线跑 Demo 也要改编排层。正确做法是只留一个接口，让「课程桩 / 生产实现 / 评测录制器」三种实现随意替换。

**第二，生成结果必须携带 token 消耗。**
如果 `generate()` 只返回 `String`，成本和配额就无从谈起——而「Agent 跑一夜烧掉两万块」是真实事故。**token 统计不是可选项，是接口的一部分。**

**第三，提示词里必须写「输出契约」。**
只说「帮我改代码」，模型会给你一段散文 + 一段代码。必须明确写清：只输出补丁、每个文件先声明路径、SEARCH 片段必须来自上下文原文。

---

## 二、是什么（What）

### 2.1 模型端口

```java
public interface CodeModel {
    GenerationOutcome generate(GenerationRequest request);
    String modelId();
}
```

三种实现，替换时只改一行装配代码：

| 实现 | 用途 |
| --- | --- |
| `StubCodeModel` | 课程 Demo：零依赖、可离线、输出确定 |
| `SpringAiCodeModel` | 生产：Spring AI `ChatClient` |
| `RecordingCodeModel` | 评测：录下每次调用便于回放 |

### 2.2 生成结果带用量

```java
public record GenerationOutcome(
        String text,
        int promptTokens,
        int completionTokens,
        long elapsedMs,
        String modelId,
        boolean fallback        // 是否降级（如主模型超时后走备用模型）
) {
    public int totalTokens() { return promptTokens + completionTokens; }
}
```

`fallback` 字段是刻意的：**评测时必须知道这次结果是主模型给的还是降级模型给的**，否则 Pass@1 数据没有可比性。

### 2.3 系统提示词（输出契约）

```java
public static final String SYSTEM_PROMPT = """
        你是一个运行在受限沙箱中的 Java 代码助手（Code Agent）。

        ## 铁律
        1. 只修改完成用户需求所必需的最少代码，不得顺带重构、改风格、加注释。
        2. 你不知道当前文件的完整内容。SEARCH 片段必须来自「上下文里给出的原文」，
           严禁凭记忆编写 —— 记忆里的代码通常是过期的，会导致补丁打不上。
        3. 一次回答可以包含多个文件，每个文件必须先用 `File: <相对路径>` 声明。
        4. 不要输出除了补丁以外的任何解释性段落。

        ## 输出格式（严格遵守）
        File: <仓库内相对路径>
        <<<<<<< SEARCH
        <要被替换的原文片段，必须与文件当前内容逐字符一致，包含缩进与空行>
        =======
        <替换后的新片段>
        >>>>>>> REPLACE

        若同一片段在文件中出现多次，SEARCH 片段必须多带几行上下文使其唯一。
        """;
```

第 2 条铁律是**从 Ch07 的失败经验反推出来的**：补丁打不上的头号原因是「模型凭记忆写旧代码」。与其等它打不上再修，不如**在提示词里就堵死**。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.generator.Chapter09Demo`

### 3.1 完整链路：上下文 → 提示词 → 模型 → 解析 → 应用

```
=== [1] 上下文选择 ===
  SelectionResult{选中 3 个文件, 丢弃 0 个, 占用 492/23768 token (2.1%)}

=== [2] 提示词装配 ===
  系统提示词 = 18 行 / 382 字符
  用户提示词 = 90 行 / 1946 字符
  估算输入 token = 1281  temperature = 0.1
```

`temperature = 0.1` 是刻意选的低值：**代码生成要的是确定性**，不是创意。这也是 Ch18 评测可复现的前提。

### 3.2 模型输出与解析应用

```
=== [3] 模型输出 ===
  GenerationOutcome{model=stub-code-model-1.0, token=1281+233, 2ms, chars=422}
  --- 原文 ---
    好的，我来修改 multiply：
    
    File: src/main/java/demo/order/Money.java
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

=== [4] 解析补丁并应用 ===
  TextPatch{src/main/java/demo/order/Money.java, +7 -3}
  → PatchResult[OK src/main/java/demo/order/Money.java 匹配 1 处, 策略=EXACT]
```

**真实模型就是这么「脏」的**：明明铁律第 4 条写了「不要输出解释性段落」，它还是先说了句「好的，我来修改 multiply：」。Ch08 的解析器正是为此准备的——**不要指望模型完全守约，要在解析层兜住**。

### 3.3 成本对比：整文件重写 vs 补丁

```
=== [6] 整文件重写 vs SEARCH/REPLACE ===
  整文件重写 191 token vs 补丁 88 token → 节省 103（53.9%）
  → 这还只是输出 token。整文件重写真正的风险是模型「顺手」改动无关代码。
```

**省了 53.9% 的输出 token**。但请注意输出里那句提醒——**成本只是第二位的收益**：

| 维度 | 整文件重写 | SEARCH/REPLACE |
| --- | --- | --- |
| 输出 token | 191 | 88（省 53.9%） |
| 顺手改无关代码 | **高风险** | 结构上不可能 |
| diff 可审阅性 | 需要额外计算 | 天然 |
| 大文件 | 直接不可行 | 可行 |
| 打不上时的反馈 | 无（直接覆盖） | 明确失败 + 建议 |

对于一个 500 行的文件，整文件重写要输出 500 行；补丁只要 10 行。**差距会随文件大小线性拉大**。

### 3.4 会犯错的模型

```
=== [7] 会犯错的模型（第一次输出缺一个分号）===
  第一次：GenerationOutcome{model=stub-code-model-1.0, token=1281+232, 0ms, chars=421}
  第一次输出故意缺分号 = true
  第二次：GenerationOutcome{model=stub-code-model-1.0, token=1281+233, 0ms, chars=422}
  → Chapter 11 会真的编译它，Chapter 13 会把编译错误喂回去让它修好
```

课程里用 `StubCodeModel.flaky()` 模拟「模型第一次漏了个分号」。**模型一定会犯错**——这正是 Ch11（编译）、Ch12（测试）、Ch13（自修复）存在的理由。

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| `StubCodeModel`（关键词模板） | Spring AI `ChatClient`（OpenAI / 通义 / 混元） |
| 固定系统提示词 | 提示词进配置中心，支持 A/B 实验与按租户定制 |
| `temperature=0.1` | 按任务类型调：重构 0.1，生成测试 0.3 |
| 单次生成 | 主模型超时 → 降级备用模型（`fallback` 标记） |

三个生产经验：

1. **提示词要版本化**。改提示词等于改模型行为，必须能回滚、能对比——把它当代码管理。
2. **`modelId` 必须记录到每条评测样本**。不同模型的 Pass@1 不能混在一起算。
3. **系统提示词里的「铁律」来自真实失败**。本课程的第 2 条铁律（不许凭记忆写代码）就是 Ch07 打不上补丁的经验结晶——**提示词应该持续从失败中生长**。

---

## 五、避坑与优化

### 坑 1：`generate()` 只返回 String
**后果**：token 无从统计，成本与配额失控。
**做法**：返回 `GenerationOutcome`，带 `promptTokens` / `completionTokens` / `fallback`。

### 坑 2：提示词不写输出格式
**后果**：模型输出散文 + 代码，解析失败率飙升。
**做法**：系统提示词里写死输出契约，并给一个完整示例。

### 坑 3：temperature 设太高
**后果**：同样的任务产出不同补丁，评测无法复现。
**做法**：代码生成用 0.0~0.2。

### 坑 4：让模型「顺便重构一下」
**后果**：diff 里混入无关改动，评审无法进行。
**做法**：铁律第 1 条明确禁止；这条约束在结构上由 SEARCH/REPLACE 保证。

### 坑 5：忽略 `fallback` 标记
**后果**：降级结果混进主模型评测数据，指标失真。
**做法**：评测报告按 `modelId` 分组。

### 优化建议
- **提示词分两层**：稳定的「角色 + 铁律 + 输出契约」作系统提示词（可缓存），变化的「RepoMap + 选中代码 + 需求」作用户提示词。
- **成本对比要持续做**：把 `compareRewriteVsPatch` 的结果记入 Trace，它能直接告诉你「这个仓库该不该继续用补丁模式」。

---

## 本章小结

- `generator` 是**唯一知道 LLM 存在的模块**；模型是端口，三种实现可替换。
- `GenerationOutcome` 必须带 token 消耗与 `fallback` 标记——成本与配额的前提。
- 系统提示词要写**输出契约**：「不许凭记忆写代码」这条铁律来自 Ch07 的真实失败。
- 整文件重写 vs 补丁：**省 53.9% 输出 token**，且真正价值是防止顺手改无关代码。
- 模型一定会犯错（Demo 里第一次就漏了分号）——这正是 Ch11~Ch13 验证与自修复存在的理由。

---

> **下一章预告**：Chapter 10 进入**命令沙箱**。补丁打进去了，接下来要「真的编译一下」——而执行模型产出的命令是整条链路里最危险的一步。我们要把它关进一个白名单 + 超时 + 环境隔离的笼子里。
>
> 👉 输入「继续」进入 Chapter 10。
