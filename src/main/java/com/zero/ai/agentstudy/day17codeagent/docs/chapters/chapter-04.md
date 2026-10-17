# Chapter 04 · 工具系统：让模型用结构化的方式操作仓库

> 本章目标：把 VFS 能力包装成模型可调用的五个工具，掌握本课最重要的约定之一——**工具失败是返回值，不是异常**；并理解「只读模式下干脆让模型看不到写工具」这一设计。

---

## 一、为什么要学（Why）

有了 VFS（Ch03），我们已经能安全地读写文件。但模型不能直接使用 Java 方法——它只能输出文本。所以必须把能力包装成**它能「调用」的东西**：工具。

这里有三个真实会踩的坑：

**坑一：失败用异常表达，模型就再也学不会了。**
如果「文件不存在」抛异常，主循环直接中断，任务失败。但实际上**文件不存在是最常见的可恢复错误**——模型只要换个名字再试就行了。正确做法是把失败**作为返回值**交给模型，让它下一轮改正。这一条决定了 Agent 能不能自我修复。

**坑二：工具描述写得太含糊，模型就乱调用。**
`description()` 会被拼进系统提示词。写「读取文件」，模型可能传绝对路径、可能传不存在的路径；写「读取仓库内某个文件的内容，可指定行区间」，模型就清楚得多。

**坑三：把写工具暴露给只读场景。**
实习生（VIEWER 角色）只想让 Agent 分析代码，你却把 `write_file` 放进提示词——模型迟早会调用它，然后被权限系统拒绝。更好的做法是：**只读模式下根本不注册写工具**，模型连它的名字都看不到。

---

## 二、是什么（What）

### 2.1 五个工具

| 工具 | 作用 | 权限 | 失败设计 |
| --- | --- | --- | --- |
| `list_files` | 列出文件路径，可按后缀过滤 | 无 | 空目录返回「（空）」 |
| `read_file` | 读文件，支持行区间 | 无 | 文件不存在 → 提示先调 `list_files` |
| `search_code` | 正则搜索，返回 `文件:行号:内容` | 无 | 正则语法错 → 给出转义建议 |
| `write_file` | 整体覆盖写入 | `CODE_WRITER` | 无权限 → 说明需要什么角色 |
| `run_command` | 执行 `java` / `javac` | `CODE_EXECUTOR` | 白名单外 → 列出允许的命令 |

### 2.2 工具契约

```java
public interface Tool {
    String name();                                       // 英文短名，模型用它指代
    String description();                                // 一句话描述，会被拼进提示词
    List<String> parameters();                           // 参数说明
    ToolResult invoke(CodeTaskContext context, Map<String, String> args);
    default String usage() { ... }                       // 拼给模型看的片段
}
```

注意 `Map<String, String>` 这个设计：**参数是模型生成的文本，本质就是弱类型**。强行定义成强类型 DTO 只会把解析问题推给框架。正确做法是让工具自己解析与校验，并通过 `parameters()` 告诉模型「参数该怎么写」。

### 2.3 失败是返回值

```java
public record ToolResult(
        String toolName,
        boolean success,
        String output,
        String error,
        long elapsedMs
) {
    public static ToolResult success(String toolName, String output, long elapsedMs) { ... }
    public static ToolResult failure(String toolName, String error, long elapsedMs) { ... }
}
```

**`error` 字段的写法有讲究**。对比：

```
// ✗ 笼统
error = "读取失败"

// ✓ 可执行（Chapter 04 真实输出）
error = "文件不存在：no/such/File.java。建议先调用 list_files 查看可用路径。"
```

后者不仅让人看懂，**还能直接喂回模型让它下一轮改正**。这是整条自修复链路的信息基础。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.tools.Chapter04Demo`

### 3.1 注册表动态生成提示词片段

工具集不是写死在提示词里的，而是**从注册表动态渲染**——这样增删工具时提示词自动同步，不会出现「提示词里写了六個工具，实际只注册了五个」的不一致：

```java
public String describe() {
    StringBuilder sb = new StringBuilder("可用工具：\n");
    for (Tool tool : tools.values()) {
        sb.append(tool.usage()).append('\n');
    }
    return sb.toString();
}
```

真实输出：

```
=== [1] 工具清单（这就是要拼进提示词的内容）===
可用工具：
- list_files：列出仓库内的文件路径，可按后缀过滤。
    参数: dir(可选)：子目录，默认仓库根, suffix(可选)：后缀过滤，如 .java
- read_file：读取仓库内某个文件的内容，可指定行区间。
    参数: path(必填)：文件相对路径, from(可选)：起始行，1-based, to(可选)：结束行
- search_code：在仓库内按正则搜索代码片段，返回「文件:行号:内容」。
    参数: pattern(必填)：Java 正则表达式, dir(可选)：限定目录, suffix(可选)：限定后缀
- write_file：把内容写入仓库内的文件（整体覆盖）。需要有写权限。
    参数: path(必填)：文件相对路径, content(必填)：文件完整内容
- run_command：执行一条命令（java / javac），用于编译与运行验证。
    参数: executable(必填)：java 或 javac, args(可选)：空格分隔的参数
```

### 3.2 五个工具的真实调用

```
=== [2] list_files ===
  list_files → OK (7ms)
      | 共 5 个文件：
      |   src/main/java/demo/order/DiscountPolicy.java
      |   src/main/java/demo/order/Money.java
      |   src/main/java/demo/order/Notes.java
      |   src/main/java/demo/order/OrderRepository.java
      |   src/main/java/demo/order/OrderService.java

=== [4] search_code ===
  search_code → OK (20ms)
      | 命中 5 处，展示前 5 条：
      |   src/main/java/demo/order/DiscountPolicy.java:10: public double discountFor(long amountCents, boolean vip) {
      |   src/main/java/demo/order/OrderService.java:9: private final DiscountPolicy discountPolicy;
      ...

=== [6] run_command（真实执行 java -version）===
  run_command → OK (1751ms)
      | openjdk version "21.0.12" 2026-07-21 LTS
```

`run_command` 那 1751ms 是**真实启动了一个子进程**——这一章的执行器还是本地的（白名单 `java/javac`），Ch10 会把它换成真正的沙箱。

### 3.3 失败即返回值（三种可恢复错误）

```
=== [7] 失败应当是返回值，不是异常 ===
  read_file → FAIL (2ms)
      | 文件不存在：no/such/File.java。建议先调用 list_files 查看可用路径。
  search_code → FAIL (2ms)
      | 正则表达式语法错误：[unclosed，Unclosed character class。建议先确认特殊字符是否需要转义。
  read_file → FAIL (5ms)
      | 参数非法：from=30 大于 to=5
```

三条失败信息都满足「可执行」标准：告诉你错在哪、下一步该做什么。这三条如果抛异常，任务就终止了；作为返回值，模型下一轮就能自己改正。

### 3.4 权限默认拒绝

```
=== [8] 权限默认拒绝 ===
  上下文 = intern-01 roles=[VIEWER] canWrite=false canExecute=false
  write_file → FAIL (0ms)
      | 当前用户 intern-01 没有写权限（需要 CODE_WRITER 或 ADMIN 角色）。建议：改为只读分析，或联系管理员申请权限。
  run_command → FAIL (0ms)
      | 当前用户 intern-01 没有命令执行权限（需要 CODE_EXECUTOR 或 ADMIN 角色）。
```

注意失败耗时是 `0ms`——**权限校验在调用入口就返回了**，根本没走到实际逻辑。

### 3.5 只读模式：让模型看不到写工具

```
=== [9] 只读模式：干脆让模型看不到写工具 ===
  只读工具 = [list_files, read_file, search_code]
  写工具可见？ write_file = false | run_command = false
  强行按名字取：find("write_file") = null（返回 null，由调用方决定降级，不抛异常）
  白名单执行器允许的可执行文件 = [javac, java]
  白名单外命令 → 命令被安全策略拒绝：可执行文件不在白名单内：cmd，允许：[javac, java]
```

两种做法的对比：

| 做法 | 效果 |
| --- | --- |
| 注册写工具 + 运行时拒绝 | 模型反复尝试调用、反复被拒，浪费轮次，还可能被模型认为「系统坏了」 |
| **只读模式不注册写工具** | 模型压根不知道有这个工具，直接走只读分析路径，零浪费 |

> 这是「默认拒绝」原则的一个延伸：**不是等它做了再拦，而是从它的认知里移除这个选项。**

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| 五个工具 | 再加 `git_diff`、`git_blame`、`run_tests`、`fetch_dependency` 等 |
| `LocalCommandExecutor`（白名单 java/javac） | `ProcessSandbox`（Ch10）→ 容器沙箱 |
| 工具描述硬编码 | 描述进配置中心，支持按租户裁剪工具集 |
| 结果限量 20 条 | 按 token 预算动态限量 |

两个生产经验：

1. **工具数量不是越多越好**。每多一个工具，就多一份提示词开销和一份误调用风险。经验值是 **5~10 个**，超出的用「二级工具」按需加载。
2. **工具名要稳定**。模型会记住工具名；一旦改名，历史提示词模板与评测集全部失效。

---

## 五、避坑与优化

### 坑 1：工具失败抛异常
**后果**：可恢复错误中断任务，模型失去自我修复的机会。
**做法**：一律返回 `ToolResult.failure`，`error` 里写「下一步该做什么」。

### 坑 2：失败信息太笼统
**后果**：模型不知道该怎么改，只能瞎重试。
**做法**：带上具体值（哪个文件、哪条正则）与可执行建议（先调 `list_files`）。

### 坑 3：工具描述写得太短
**后果**：模型乱传参数（绝对路径、不存在的路径）。
**做法**：`description()` 里写清作用域（「仓库内」、相对路径）与必填/可选。

### 坑 4：只读场景也暴露写工具
**后果**：模型反复尝试被拒，浪费轮次。
**做法**：用 `ToolRegistry.readOnly(vfs)` 只注册只读工具。

### 坑 5：搜索结果不设上限
**后果**：一个 `.*` 正则把整个仓库倒进上下文，token 爆炸。
**做法**：限量 + 截断提示（Demo 限 20 条，并明确告知「仅展示前 N 条」）。

### 优化建议
- **参数校验失败要给出正确示例**：比「参数非法」更有用的是「参数非法：from=30 大于 to=5」。
- **`find()` 返回 null 而非抛异常**：调用方自己决定降级策略，不要替它做决定。

---

## 本章小结

- 工具 = 「模型可调用的动作」；参数用 `Map` 承载，因为模型输出本质是弱类型文本。
- **工具失败是返回值，不是异常**——这是 Agent 能自我修复的信息基础。
- 失败信息必须**可执行**：写清错在哪、下一步该做什么。
- 权限**默认拒绝**，且校验在入口完成（0ms 返回）。
- **只读模式干脆不注册写工具**：不是等它做了再拦，而是从它的认知里移除。
- 工具清单由注册表动态渲染，避免提示词与实际能力不一致。

---

> **下一章预告**：Chapter 05 进入**静态分析与仓库地图**。工具让模型能「翻文件」，但仓库太大时它根本翻不过来——我们需要先给它一张一页纸的索引卡（RepoMap），让它知道该翻哪。
>
> 👉 输入「继续」进入 Chapter 05。
