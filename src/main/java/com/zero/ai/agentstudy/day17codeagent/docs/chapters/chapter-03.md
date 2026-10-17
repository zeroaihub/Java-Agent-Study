# Chapter 03 · 虚拟文件系统：一切写操作的唯一入口

> 本章目标：理解为什么 Code Agent 必须有一个「唯一写入口」，掌握路径穿越防护的正确写法（以及那个经典陷阱），并用内容指纹识别「看起来在忙其实一动没动」的隐蔽失败。

---

## 一、为什么要学（Why）

LLM 生成的路径是**不可信输入**。它可能因为幻觉写出 `../../../../etc/passwd`，也可能被提示词注入引导去改仓库外的文件。

最容易犯的架构错误是：让「读文件工具」「写文件工具」「补丁应用器」各自调用 `Files.write(path)`，然后在每个地方都写一遍路径校验。这样会出两个问题：

1. **校验必然会被遗漏**：三个地方只要漏一个，整个防线就形同虚设，而且漏洞极难被发现；
2. **规则会漂移**：一处用 `normalize()`，另一处忘了，两处行为不一致，调试时无从下手。

正确做法是：**把「这条路径能不能落盘」收敛成唯一的一道门**。这就是 `VirtualFileSystem`（VFS）。

第二个动机来自一类隐蔽失败：**「看起来在忙，其实一动没动」**。LLM 连续三轮给出的补丁，打完之后文件内容与上一轮完全一致。如果只看「有没有报错」，循环会一直空转到重试上限，然后对外宣称「修复失败」——浪费了一整轮配额，还让人以为模型在努力。

要识别它，就得有**内容指纹**。

---

## 二、是什么（What）

### 2.1 VFS 的三条铁律

| 铁律 | 含义 | 为什么 |
| --- | --- | --- |
| **相对路径进，绝对路径出** | 外部只传仓库内相对路径，绝对路径由 VFS 拼装 | 外部永远拿不到真实磁盘路径，也就无法构造「跳出去」的路径 |
| **规范化和根校验** | `normalize()` 消解 `..`，再与根做**按路径元素**比较 | 判断依据是「真实落点」，不是「路径里有没有 `..`」 |
| **写入设体积上限** | 默认单文件 1 MB | LLM 一次吐几十兆内容是真实存在的，必须在写之前挡住 |

### 2.2 快照与指纹

`RepoSnapshot` 是某一时刻「路径 → 内容」的不可变视图 + SHA-256 指纹。它有两个用途：

- **无进展检测**（Ch13）：改前改后指纹相同 → 判定 `REPAIR_NO_PROGRESS`，提前退出；
- **变更集计算**（Ch17）：比逐文件比对字符串便宜得多，且天然忽略「内容没变但修改时间变了」的文件。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.repo.Chapter03Demo`

### 3.1 心脏方法：`resolve()`

```java
public Path resolve(String relativePath) {
    CodeException.requireText(relativePath, "relativePath");

    // 1. 统一分隔符（Windows 反斜杠 → /）
    String unified = relativePath.replace('\\', '/');

    // 2. 绝对路径输入本身就是越权信号
    if (unified.startsWith("/") || unified.matches("^[A-Za-z]:.*")) {
        throw new CodeException(CodeErrorCode.PATH_TRAVERSAL,
                "不接受绝对路径：" + relativePath + "，请提供仓库内相对路径");
    }

    // 3. 消解 . 与 ..
    Path candidate = root.resolve(unified).normalize().toAbsolutePath();

    // 4. 按「路径元素」比较根 —— 不能用字符串 startsWith
    if (!candidate.startsWith(root)) {
        throw new CodeException(CodeErrorCode.PATH_TRAVERSAL,
                "路径越界：" + relativePath + " → " + candidate + " 不在仓库根 " + root + " 之内");
    }
    return candidate;
}
```

**第 4 步是本课程最重要的一行代码之一**。用字符串做根校验是错的：

```java
// ✗ 错误：根 /tmp/work 会放行 /tmp/workplace-evil/x
candidate.toString().startsWith(root.toString())

// ✓ 正确：Path.startsWith 按路径元素比较，workplace ≠ work
candidate.startsWith(root)
```

Demo 里专门做了对照实验（真实输出）：

```
=== [4] 为什么不能用字符串 startsWith 做根校验 ===
  仓库根         = C:\Users\13153\AppData\Local\Temp\day17-vfs\tenant-acme__order-service__task-001
  越界候选       = C:\Users\13153\AppData\Local\Temp\day17-vfs\tenant-acme__order-service__task-001-evil\secret.txt
  字符串 startsWith 判定 = true（错误地放行了）
  Path.startsWith 判定   = false（正确地拦截了）
```

### 3.2 四种攻击姿势 + 一个反例

Demo 用五种路径验证防护，真实输出如下：

```
=== [3] 路径越界防护（四种攻击姿势）===
  ✓ 已拦截 [CA-1004] ../../../../etc/passwd
  ✓ 已拦截 [CA-1004] src/../../../secret.txt
  ✓ 已拦截 [CA-1004] /etc/hosts
  ✓ 已拦截 [CA-1004] C:/Windows/System32/config/SAM
  ✓ 已拦截 [CA-1004] src/main/java/./demo/../../../../../evil.java
  ⓘ 对照 - 这条看起来可疑，但向上 4 级正好停在仓库根内部，合法：
     src/main/java/./demo/../../../../evil.java → ...\day17-vfs\tenant-acme__order-service__task-001\evil.java
     判定不在枪口下的依据是「规范化后的真实落点」，而不是路径里有没有 ..
```

最后那个**反例是本课程特意保留的**：`src/main/java/./demo/../../../../evil.java` 里明明有四个 `..`，但它规范化后正好落在仓库根内部，是**合法**的。

> 这一点在教学上很重要：如果你写了一个「路径里包含 `..` 就拒绝」的校验，你会误杀合法路径，而且你会以为自己在做安全。**安全的判据只能是规范化后的真实落点。**

### 3.3 内容指纹：只看内容，不看时间

```java
public static String fingerprintOf(Map<String, String> sorted) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    // 分隔符必须是 byte[]：MessageDigest 没有 update(int) 重载
    byte[] separator = {0};
    for (Map.Entry<String, String> e : sorted.entrySet()) {
        digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
        digest.update(separator);
        digest.update(e.getValue() == null ? new byte[0]
                : e.getValue().getBytes(StandardCharsets.UTF_8));
        digest.update(separator);
    }
    ...
}
```

三个细节：

- **用 `TreeMap` 保证遍历顺序稳定**：否则同一份内容可能算出不同指纹；
- **路径与内容之间插分隔符 `\0`**：否则 `{"a":"bc"}` 与 `{"ab":"c"}` 会碰撞；
- **分隔符必须是 `byte[]`**：`MessageDigest` 没有 `update(int)` 重载（这是实现时真实踩到的编译错误）。

真实输出：

```
=== [5] 快照指纹与变更检测 ===
  初始指纹 = a434d25f9c12c536  文件数 = 6
  追加一行后指纹 = b964fe6df23e4807  sameAs(before) = false
  差异 = Diff{+0 ~1 -0}  修改文件 = [README.md]
  改回原文后 sameAs(before) = true（内容指纹只看内容，不看修改时间）
```

注意最后一行：**改回原文后指纹又变回去了**。这就是 Ch13 无进展检测的基础——如果一轮修复打完补丁后指纹没变，说明模型输出的补丁什么也没改。

### 3.4 一个刻意的排序决定

```java
public List<String> listFiles(String relativeDir) {
    ...
    result.sort(Comparator.naturalOrder());   // 排序是刻意的
    return List.copyOf(result);
}
```

**为什么排序这么重要**：LLM 对输入顺序敏感。随机顺序会让同样的任务产出不同的补丁，评测无法复现。稳定排序是「可复现」的前提——这一点在 Ch18 做 Pass@k 评测时会再次体现。

---

## 四、真实项目中的应用

| 课程实现 | 生产替换 | 注意 |
| --- | --- | --- |
| `SampleRepository` 内存夹具 | JGit 拉取真实分支到临时目录 | 目录名必须带租户+仓库+任务三级前缀 |
| `VirtualFileSystem` 相对路径 | 同上，但需额外处理符号链接 | **符号链接能绕过根校验**，生产必须 `--no-same-owner` 或在容器内解析真实路径 |
| 1 MB 单文件上限 | 按仓库配置，并加**整仓库体积配额** | 只限单文件挡不住「生成一万个文件」 |
| 内存快照 | 落库 `repo_snapshot` 表（见 DATABASE.md） | 指纹字段用于审计「这次任务到底改了什么」 |

一个真实的落地形态：

```
/work/vfs/
  ├── tenant-acme__order-service__task-001/     ← CodeTaskContext.sandboxDirectoryName()
  ├── tenant-acme__order-service__task-002/
  └── tenant-beta__pay-service__task-001/       ← 另一租户，物理隔离
```

目录名用 `租户__仓库__任务`，即使上层路由出错，文件系统这一层也不会串租户。

---

## 五、避坑与优化

### 坑 1：用字符串 `startsWith` 做根校验
**后果**：`/tmp/work` 放行 `/tmp/workplace-evil/x`，越权写入。
**做法**：`Path.startsWith(Path)`，按路径元素比较。

### 坑 2：见到 `..` 就拒绝
**后果**：误杀合法路径（`src/main/java/./demo/../../../../evil.java` 其实是落在仓库内的）。
**做法**：先 `normalize()`，再看真实落点。

### 坑 3：忘记处理 Windows 盘符与反斜杠
**后果**：Windows 上 `C:/Windows/...` 或 `a\b` 绕过分隔符假设。
**做法**：统一替换为 `/`，并单独拦截盘符开头。

### 坑 4：指纹用 `HashMap` 遍历
**后果**：遍历顺序不定，同一内容算出不同指纹，无进展检测时灵时不灵。
**做法**：`TreeMap` + 路径内容间插分隔符。

### 坑 5：只限单文件体积
**后果**：Agent 生成一万个 900KB 的文件，磁盘照样爆。
**做法**：单文件上限 + 整仓库配额 + 任务结束清理（见 DEPLOY.md）。

### 优化建议
- **`find()` 而非 `read()`**：探测文件是否存在时用 `find()`（返回 `null`），别用异常控制流程——这是「工具失败是返回值」原则的第一处体现。
- **符号链接**：生产环境要额外处理。JDK 的 `Path.toRealPath()` 可以解析真实路径，但要注意它要求文件已存在。

---

## 本章小结

- VFS 是**唯一写入口**：安全校验收敛到一处，别处没有机会绕过。
- 三条铁律：相对路径进绝对路径出、`normalize()` + `Path.startsWith` 根校验、写入体积上限。
- **判据是规范化后的真实落点，不是路径里有没有 `..`**——含 `..` 的合法路径真实存在。
- 内容指纹（SHA-256 + TreeMap + 分隔符）让「一动没动」的隐蔽失败无处遁形，是 Ch13 无进展检测的地基。
- 稳定排序是**可复现**的前提，直接影响 Ch18 评测的可信度。

---

> **下一章预告**：Chapter 04 进入**工具系统**。有了安全的读写入口，下一步是把它包装成模型能调用的五个工具，并回答一个关键问题：工具失败时，应该抛异常还是返回结果？
>
> 👉 输入「继续」进入 Chapter 04。
