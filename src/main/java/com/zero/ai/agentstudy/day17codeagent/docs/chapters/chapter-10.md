# Chapter 10 · 命令沙箱：让「执行」可控、可超时、可隔离

> 本章目标：把命令执行关进白名单 + 超时 + 环境隔离的笼子，理解为什么 `timedOut` 与 `exitCode` 必须是两个独立维度，以及为什么子进程输出**必须异步读取**。

---

## 一、为什么要学（Why）

到 Chapter 09，模型已经能产出补丁了。但要验证它，就必须**真的编译、真的运行**——而这是整条链路里最危险的一步：

- 模型产出的代码可能包含 `System.exit(0)`、`Runtime.exec("rm -rf /")`、死循环；
- 它可能读环境变量把密钥打印出来；
- 它可能写一个无限递归把内存吃光。

同时还有两个**极其隐蔽**的工程陷阱：

**陷阱一：`timedOut` 与 `exitCode` 混为一谈。**
如果只判 `exitCode != 0`，那么「超时被强杀」会被当成「编译报错」，自修复循环就会**对着一个根本不存在的语法错误反复重试**——白白烧掉全部轮次。

**陷阱二：同步读取子进程输出导致假死。**
子进程的 stdout 缓冲区只有几 KB。如果主线程先 `waitFor()` 再读输出，缓冲区写满后子进程会阻塞在 `write()` 上，永远不会退出。表现是：**一个本来 100ms 的程序，永远等不到结束，全部超时**。这是「莫名其妙所有命令都超时」的头号原因。

---

## 二、是什么（What）

### 2.1 四道闸门

| 闸门 | 检查内容 | 失败错误码 |
| --- | --- | --- |
| 1. 可执行文件白名单 | 只允许 `java` / `javac` | `CA-6004 SANDBOX_COMMAND_DENIED` |
| 2. 参数黑名单 | 即使可执行文件合法，参数命中 `rm -rf` / `sudo` 等也拒绝 | 同上 |
| 3. 工作目录包含性 | 必须在沙箱根之内 | 同上 |
| 4. 环境变量隔离 | 默认 `inheritEnv=false` | — |

### 2.2 策略对象

```java
public record SandboxPolicy(
        long timeoutMs,
        int maxOutputBytes,
        Set<String> allowedExecutables,
        Set<String> forbiddenArgs,
        boolean inheritEnv,
        int maxAttempts
) {
    public static SandboxPolicy defaults() {
        return new SandboxPolicy(
                30_000L,
                256 * 1024,
                Set.of("java", "javac"),
                Set.of("rm -rf", "sudo", "shutdown", "format", "del /f", "mkfs", ":(){", ">/dev/sd"),
                false,   // 不继承宿主环境变量
                3);
    }

    public SandboxPolicy {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("沙箱必须有超时：没有超时的执行等于给 Agent 一个死循环开关");
        }
        ...
    }
}
```

`timeoutMs <= 0` 直接拒绝——**没有超时的执行等于给 Agent 一个死循环开关**。

### 2.3 执行结果：两个独立维度

```java
public record ExecResult(
        boolean ok,
        Integer exitCode,      // 超时时为 null
        String stdout,
        String stderr,
        long elapsedMs,
        boolean timedOut,      // 与 exitCode 独立
        String message
) { }
```

| 情况 | `ok` | `exitCode` | `timedOut` |
| --- | --- | --- | --- |
| 执行成功 | true | 0 | false |
| 编译报错 | false | 1 | false |
| 超时强杀 | false | **null** | **true** |
| 被策略拒绝 | false | null | false |

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.sandbox.Chapter10Demo`

### 3.1 白名单内：真实执行

```
=== [2] 白名单内：真实执行 java -version ===
  ExecResult{ok=true, exitCode=0, timedOut=false, elapsedMs=1683, message=执行成功}
      openjdk version "21.0.12" 2026-07-21 LTS
```

### 3.2 白名单外与参数黑名单

```
=== [3] 白名单外可执行文件 ===
  cmd      → 命令被安全策略拒绝：可执行文件不在允许列表内或参数命中黑名单：cmd [--version]
  rm       → 命令被安全策略拒绝：可执行文件不在允许列表内或参数命中黑名单：rm [--version]
  python   → 命令被安全策略拒绝：可执行文件不在允许列表内或参数命中黑名单：python [--version]
  sh       → 命令被安全策略拒绝：可执行文件不在允许列表内或参数命中黑名单：sh [--version]

=== [4] 参数黑名单（可执行文件合法也没用）===
  命令被安全策略拒绝：可执行文件不在允许列表内或参数命中黑名单：java [-version;, rm, -rf, /]
```

第 4 条很关键：`java` 本身合法，但参数里出现 `rm -rf /` 依然被拒。**两道门是「与」关系，不是「或」**——只查可执行文件是不够的，因为 `java` 也能通过 `-e` 或类路径做很多事。

### 3.3 工作目录越界

```
=== [5] 工作目录越界 ===
  命令被安全策略拒绝：工作目录越界：C:\Users\13153，必须位于沙箱根 C:\...\day17-sandbox4778228568762549751 之内
```

与 Ch03 的 VFS 根校验是同一套思路（`Path.startsWith`），只不过这里管的是**进程的工作目录**。

### 3.4 超时强杀：两个维度必须分开

```
=== [6] 超时强杀：这里给 javac 一个几乎不可能完成的超时窗口 ===
  ExecResult{ok=false, exitCode=null, timedOut=true, elapsedMs=1753, message=执行超时已被强制终止}
  timedOut = true | ok = false | exitCode = null
  → 关键：timedOut 与 exitCode 是两个独立维度。只判 exitCode != 0 会把「超时」误当成「编译报错」，
    自修复循环就会对着根本不存在的语法错误反复重试

=== [7] 给足时间：同一次编译可以成功 ===
  ExecResult{ok=true, exitCode=0, timedOut=false, elapsedMs=526, message=执行成功}
  产物是否存在 = true
```

同样的 `javac`，60ms 超时 → 超时；给足时间 → 成功并产出 class。这证明**超时确实是超时，不是编译错误**。

### 3.5 环境变量隔离

```
=== [8] 为什么必须 clears 宿主环境变量 ===
  宿主环境变量数量 ≈ 237
  沙箱内继承？ policy.inheritEnv = false
  → 一旦继承，Agent 生成的代码可以 System.getenv("OPENAI_API_KEY") 把密钥打出来
```

237 个环境变量里几乎必然有敏感项。清空后只注入必要的 `PATH` 与 `JAVA_HOME`：

```java
pb.environment().clear();
if (policy.inheritEnv()) {
    pb.environment().putAll(System.getenv());
}
pb.environment().put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());
pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
```

### 3.6 为什么输出必须异步读

```
=== [9] 为什么(subprocess)输出必须异步读 ===
  子进程 stdout 缓冲区只有几 KB：
  如果主线程先 waitFor 再读，缓冲区写满后子进程会阻塞，
  于是「一个本来 100ms 的程序」永远等不到结束 —— 表现为莫名其妙全部超时。
```

实现上用两个 `StreamGobbler` 线程分别读 stdout / stderr，主线程负责 `waitFor` 与超时强杀：

```java
StreamGobbler stdout = new StreamGobbler(process.getInputStream(), policy.maxOutputBytes());
StreamGobbler stderr = new StreamGobbler(process.getErrorStream(), policy.maxOutputBytes());
...
boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);
if (!finished) {
    process.destroyForcibly();   // 强杀
}
```

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 |
| --- | --- |
| `ProcessSandbox`（进程级） | **容器级**：Docker / gVisor / Kata |
| 白名单 `java/javac` | 加上 `mvn` / `gradle`（但需更严格的目录限制） |
| `inheritEnv=false` | 加 `--network=none`、只读根文件系统、cgroups 限额 |
| 输出截断 | 输出落文件，只把摘要喂回模型 |

**课程沙箱的局限必须诚实说明**：进程级沙箱**不隔离文件系统与网络**。如果 Agent 生成的代码访问网络，进程级沙箱拦不住。所以 DEPLOY.md 里明确要求容器层加 `--network=none`。

一个真实经验：**编译任务通常不需要网络**。这一条能同时掐断「把源码传出去」和「下载并执行恶意依赖」两条路，收益极高。

---

## 五、避坑与优化

### 坑 1：只判 `exitCode != 0`
**后果**：超时被当成编译错误，自修复循环对着不存在的错误反复重试，烧光轮次。
**做法**：`timedOut` 与 `exitCode` 分开；超时时 `exitCode=null`。

### 坑 2：同步读取子进程输出
**后果**：缓冲区写满 → 子进程阻塞 → 永远不退出 → 全部超时。
**做法**：独立线程读 stdout / stderr（两个都要！只读 stdout 一样会阻塞）。

### 坑 3：只查可执行文件不查参数
**后果**：`java` 白名单内，但参数里能做很多事。
**做法**：可执行文件白名单 + 参数黑名单，**与关系**。

### 坑 4：继承宿主环境变量
**后果**：`System.getenv("OPENAI_API_KEY")` 把密钥打进日志。
**做法**：`inheritEnv=false`，清空后只注入必要变量。

### 坑 5：没有超时
**后果**：死循环把机器跑挂。
**做法**：`timeoutMs <= 0` 构造期拒绝；超时后 `destroyForcibly()`。

### 优化建议
- **`runtimeOnly()` 策略**：CI 场景连 `javac` 都不给，只允许跑已编译的程序，攻击面进一步缩小。
- **输出限量**：`maxOutputBytes` 截断并标记，避免模型把几 MB 的编译输出吞进上下文。

---

## 本章小结

- 执行是整条链路最危险的一步，必须关进四道闸门：可执行文件白名单、参数黑名单、工作目录包含性、环境隔离。
- **`timedOut` 与 `exitCode` 是两个独立维度**：超时时 `exitCode=null`，否则自修复会对着不存在的错误重试。
- **子进程输出必须异步读**（stdout 与 stderr 都要），否则缓冲区写满导致假死——这是「全都超时」的头号原因。
- `inheritEnv=false`：宿主有 237 个环境变量，继承等于把密钥递给 Agent。
- 进程级沙箱不隔离文件与网络，生产必须再加容器层（见 DEPLOY.md）。

---

> **下一章预告**：Chapter 11 进入**编译验证**。沙箱能执行命令了，下一步是用 `javax.tools.JavaCompiler`（javac 官方 API）做真正的编译，并把诊断信息**结构化**——这样才能精确告诉模型「第几行、什么错」。
>
> 👉 输入「继续」进入 Chapter 11。
