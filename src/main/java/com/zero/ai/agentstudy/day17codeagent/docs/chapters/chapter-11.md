# Chapter 11 · 编译验证：用 javac 官方 API 做结构化诊断

> 本章目标：用 `javax.tools.JavaCompiler`（javac 官方 API）做真正的编译，把诊断信息**结构化**成「文件 / 行 / 列 / 错误码 / 消息」，并生成能直接喂回模型的反馈。

---

## 一、为什么要学（Why）

自修复循环能不能工作，取决于**反馈的质量**。对比两种反馈：

```
// ✗ 非结构化：把 javac 的控制台输出整段粘回去
反馈：javac 1.8: 错误: 需要';'
      Money.java:29: error: ';' expected
      ...（几十行噪音）

// ✓ 结构化（Chapter 11 真实输出）
- 错误 src/main/java/demo/order/Money.java 第 29 行：';' expected
```

前者模型要自己从噪音里找行号，而且**无法判断是否「两轮犯了同一个错」**。后者可以直接用于：

1. **精确定位**：第 29 行、什么错；
2. **无进展检测**：把「文件:行:消息」做成签名，比较两轮签名集合是否相同（Ch13）；
3. **限量**：只喂前 5 条，剩下的告知「还有 N 个」。

还有一个关键问题：**怎么调 javac？**

| 方式 | 问题 |
| --- | --- |
| `Runtime.exec("javac ...")` | 要拼命令行、要处理转义、要解析文本输出、**跨平台行为不一致** |
| `javax.tools.JavaCompiler` | 官方 API，诊断结构化，跨平台一致 |

---

## 二、是什么（What）

### 2.1 结构化诊断

```java
public record Diagnostic(
        Kind kind,        // ERROR / WARNING / NOTE / OTHER
        String file,
        int line,
        int column,
        String code,      // 编译器错误码，如 compiler.err.expected
        String message
) {
    /** 用于「两轮是否犯同一个错」的比较。 */
    public String signature() { return file + ":" + line + ":" + message; }
}
```

`code` 字段（如 `compiler.err.expected`）是 javac 的**稳定错误码**，比消息文本更适合做规则匹配（消息文本会随 JDK 版本和 locale 变化）。

### 2.2 编译结果

```java
public record CompilationResult(
        boolean success,
        List<Diagnostic> diagnostics,
        int fileCount,
        long elapsedMs,
        Path outputDir,
        String summary
) {
    public List<Diagnostic> errors() { ... }
    public List<String> errorSignatures() { ... }   // 无进展检测用
    public String feedbackForModel(int maxItems) { ... }  // 喂回模型
}
```

### 2.3 两种编译模式

| 模式 | 用途 | 耗时（实测） |
| --- | --- | --- |
| `compile(vfs, outputDir)` | 整仓编译，产出 class 供测试用 | 438 ms / 4 文件 |
| `compileSnippet(className, source)` | 内存编译单个片段，只做语法检查 | **29 ms**，后续 8 ms |

**片段编译快一个数量级**。它的用途是：在把补丁真正落盘之前，先快速验证「这段新代码语法对不对」，避免在自修复循环里反复全量编译。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.verify.Chapter11Demo`

### 3.1 运行环境自检（必做）

```
=== [0] 运行环境自检 ===
  JavaCompiler 可用 = true
```

```java
public static boolean available() {
    return ToolProvider.getSystemJavaCompiler() != null;
}
```

**这一步在生产上是必须的**：JRE 环境下 `getSystemJavaCompiler()` 返回 `null`，编译验证直接失效，而自修复循环会因为收不到反馈彻底失效。DEPLOY.md 里把它列进了 readiness 探针。

### 3.2 基线编译

```
=== [1] 基线编译（未修改的仓库）===
  CompilationResult{编译通过, files=4, errors=0, warnings=0, 438ms}
```

### 3.3 打上缺分号的补丁 → 结构化诊断

```
=== [2] 打上一个缺分号的补丁 ===
  应用补丁 → 成功
  CompilationResult{编译失败：共 1 个错误, files=4, errors=1, warnings=0, 23ms}
  --- 结构化诊断 ---
    [错误] src/main/java/demo/order/Money.java:29 ';' expected (compiler.err.expected)
  --- 喂给模型的反馈 ---
      编译未通过，请修正以下错误后重新输出补丁：
      - 错误 src/main/java/demo/order/Money.java 第 29 行：';' expected
```

这就是自修复循环的输入。**注意反馈是限量的**：

```java
public String feedbackForModel(int maxItems) {
    if (success) {
        return "编译通过，无需修改。";
    }
    StringBuilder sb = new StringBuilder("编译未通过，请修正以下错误后重新输出补丁：\n");
    errors().stream().limit(maxItems)
            .forEach(d -> sb.append("- ").append(d.forPrompt()).append('\n'));
    int rest = errors().size() - Math.min(maxItems, errors().size());
    if (rest > 0) {
        sb.append("- （还有 ").append(rest).append(" 个错误未列出，请一并检查）\n");
    }
    return sb.toString();
}
```

为什么要限量？因为一个语法错误可能引发**几十个级联错误**（比如少了个 `}`，后面全乱）。全喂进去既费 token 又干扰模型——只喂前 5 条，剩下的告知「还有 N 个」。

### 3.4 修好后重新编译

```
=== [3] 修好后重新编译 ===
  PatchResult[OK src/main/java/demo/order/Money.java 匹配 1 处, 策略=EXACT]
  CompilationResult{编译通过, files=4, errors=0, warnings=0, 78ms}
  → 编译通过，且新增的 multiply 方法确实存在：
      public Money multiply(long factor) {
      return new Money(this.amount.multiply(BigDecimal.valueOf(factor)));
```

注意第二次编译只用了 78ms（首次 438ms）——**JVM 的类加载与 JIT 预热**。生产上可以做编译缓存进一步摊薄。

### 3.5 内存片段编译

```
=== [4] 内存片段编译（不落盘，快得多）===
  合法片段 → CompilationResult{片段编译通过, files=1, errors=0, warnings=0, 29ms}
  缺分号   → CompilationResult{片段编译失败：1 个错误, files=1, errors=1, warnings=0, 8ms}
      [错误] Probe.java:1 ';' expected (compiler.err.expected)
  单次片段编译耗时 ≈ 14 ms（首次含类加载，后续更快）
```

这里有一个**真实踩到的坑**。想把 class 输出拦在内存里，最直觉的写法是：

```java
// ✗ 会抛 IllegalArgumentException: empty path for directory
fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of());
```

正确做法是实现一个 `ForwardingJavaFileManager`，重写 `getJavaFileForOutput` 返回内存中的 `SimpleJavaFileObject`：

```java
InMemoryFileManager inMemoryManager = new InMemoryFileManager(fileManager);

JavaCompiler.CompilationTask task = compiler.getTask(
        null, inMemoryManager, collector,
        List.of("-encoding", "UTF-8", "-proc:none"),
        null, List.of(unit));
```

这样编译检查期间**磁盘上不会留下任何临时文件**——对高频调用的验证场景很重要。

### 3.6 错误签名：无进展检测的基础

```
=== [5] 错误签名（用于判断「两轮是否犯同一个错」）===
  [Probe.java:1:';' expected]
  若第二轮的错误签名集合与第一轮完全重合，说明模型没听懂反馈 —— 应立即停止重试而不是烧到上限
```

`signature() = file + ":" + line + ":" + message`。Ch13 会用这个签名集合做「无进展检测」——**这是自修复循环最重要的刹车**。

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| `javax.tools.JavaCompiler` 全量编译 | 增量编译 + 编译缓存（按文件 hash） |
| 编译整个 VFS | 只编译「变更文件 + 影响面文件」 |
| 诊断限量 5 条 | 按错误类型聚类（同一种错只报一次 + 出现次数） |
| 内存片段编译 | 保留，用于「补丁落盘前的预检」 |
| 无并发 | 多任务编译时用独立输出目录（避免 class 互相覆盖） |

一个真实经验：**编译输出目录必须按任务隔离**。Demo 里用了 `day17-classes-baseline...` 这样的随机目录名——如果多个任务共用一个 classes 目录，并发任务会互相覆盖 class，导致「明明编译通过了却跑出错的class」。

---

## 五、避坑与优化

### 坑 1：用 `Runtime.exec("javac")` 拼命令行
**后果**：转义地狱、输出解析脆弱、跨平台不一致。
**做法**：`javax.tools.JavaCompiler`，诊断结构化。

### 坑 2：不检查编译器可用性
**后果**：JRE 环境下静默失效，自修复循环收不到反馈。
**做法**：`available()` 自检，并纳入 readiness 探针。

### 坑 3：`setLocation(CLASS_OUTPUT, List.of())`
**后果**：`IllegalArgumentException: empty path for directory`。
**做法**：自定义 `ForwardingJavaFileManager` 拦截输出。

### 坑 4：错误全量喂回模型
**后果**：一个语法错引发几十条级联错误，token 爆炸且干扰模型。
**做法**：限量 + 告知剩余数量。

### 坑 5：多任务共用 classes 目录
**后果**：并发任务互相覆盖 class。
**做法**：输出目录按任务隔离。

### 坑 6：忘了 `-encoding UTF-8`
**后果**：中文注释导致编译失败，且报错信息本身也是乱码。
**做法**：编译选项显式带 `-encoding UTF-8`。

### 优化建议
- **片段编译做预检**：补丁落盘前先 `compileSnippet` 快速验证，通过再落盘，能省下大量全量编译。
- **错误签名入库**：长期统计「哪类错误最常出现」，反向优化提示词。

---

## 本章小结

- 编译验证用 `javax.tools.JavaCompiler`（javac 官方 API），诊断天然结构化。
- `Diagnostic` 携带 `file / line / column / code / message`；`code`（如 `compiler.err.expected`）比消息文本更稳定。
- `feedbackForModel()` 限量 5 条并告知剩余——这是自修复循环的输入。
- `errorSignatures()` 让「两轮是否犯同一个错」可判定，是 Ch13 无进展检测的基础。
- 内存片段编译（29ms）比整仓编译（438ms）快一个数量级，适合做落盘前预检；注意 `CLASS_OUTPUT` 空列表会抛异常。
- 生产必须：编译器可用性自检、输出目录按任务隔离、`-encoding UTF-8`。

---

> **下一章预告**：Chapter 12 进入**测试验证**。我们会用一个真实的反例证明：**编译通过 ≠ 正确**——把 `Money.zero()` 改成返回 1，编译照样通过，只有测试能抓住它。
>
> 👉 输入「继续」进入 Chapter 12。
