# Chapter 01 · 为什么需要 Code Agent

> 本章目标：讲清楚「让 LLM 写代码」与「让 LLM **交付** 代码」之间的鸿沟，建立 Day17 的总体心智模型，并认识贯穿全部 20 章的三块地基代码（`CodeTaskContext` / `CodeResult` / `CodeException`）。

---

## 一、为什么要学（Why）

如果你用过代码补全工具，你大概经历过这种落差：

> 模型在对话框里给出了一段漂亮的代码，你复制到工程里——**编译报错**。
> 你把报错粘回去，它道歉并给出新版本——**这次编译过了，但行为不对**。
> 你继续粘贴，来回十轮，最后你意识到：**自己手动改可能早就改完了**。

问题不在于模型不够聪明，而在于我们让它做的事只完成了一半。看这张表，每一行都是真实会被工程师吐槽的场景：

| 现象 | 根因 |
| --- | --- |
| 生成的代码根本编译不过 | **没有验证闭环** |
| 改对了语法，改错了语义 | 只有编译，**没有测试** |
| 一次重写整个文件，顺手改掉无关代码 | 没有「最小改动」约束 |
| 补丁打不上（凭记忆写旧代码） | 上下文给的是模型记忆，**而非仓库现状** |
| 仓库太大塞不进上下文 | 没有上下文预算与选择 |
| 它想改 CI 配置、或者代码里加了密钥 | **没有落盘前的安全闸门** |
| 死循环把机器跑挂 | 没有沙箱与超时 |
| 循环重试一整晚，烧掉几千块 | **没有预算护栏与无进展检测** |
| PR 里只有一句「已修复」 | 没有变更集与 PR 生成 |
| 出了问题不知道是哪一步坏的 | 没有全链路 Trace |

**结论**：企业需要的不是一个「会写代码的对话框」，而是一个**能读仓库、能最小改动、能自己验证、能自己修错、能守规矩、能交代清楚**的工程角色。这就是 Code Agent，也是本课程 20 章要逐一攻克的目标。

---

## 二、是什么（What）

### 2.1 从「输出代码」到「交付改动」

Demo 级的代码生成是一条直线：

```
需求 → 拼 Prompt → LLM 输出代码文本 → （人肉复制粘贴 → 人肉编译 → 人肉修）
```

注意括号里的部分——**全是人肉**。Code Agent 要做的，就是把括号里的部分接管过来，变成一条闭环：

```
                    ┌──────────────────────────────────────────────────┐
需求 + CodeTaskContext │  读仓库 → 选址 → 生成补丁 → 安全审查 → 应用      │
   ─────────────────▶ │      → 编译 → 测试 → 自修复 → 评审 → 变更集 → PR │
                    └──────────────────────────────────────────────────┘
                                          │
                    ┌─────────────────────┼─────────────────────┐
                    ▼                     ▼                     ▼
                成功可合并          编译/测试未通过          被安全策略拒绝
             （PR + Trace）      （自修复或明确失败）     （转人工，不留半成品）
```

每一个节点都对应本课程的一章：

- **读仓库（Ch03-Ch05）**：VFS 是唯一写入口；静态分析产出符号表与依赖图；RepoMap 是给模型的一页索引卡。
- **选址（Ch06）**：上下文预算与选择，把有限的窗口花在刀刃上。
- **生成补丁（Ch07-Ch09）**：SEARCH/REPLACE 最小改动；从脏输出里解析；提示词装配与成本对比。
- **执行与验证（Ch10-Ch12）**：沙箱、编译验证、测试验证。
- **自修复（Ch13）**：把编译错误喂回去让它改，但必须有刹车。
- **守规矩（Ch14-Ch15）**：落盘前的安全闸门；只评审增量的规则式评审。
- **交得清（Ch16-Ch17）**：自动生成测试；变更集与 PR 草稿。
- **看得见（Ch18-Ch19）**：Pass@k 评测；Trace 与预算护栏。
- **串起来（Ch20）**：编排层与 REST API。

### 2.2 三条贯穿原则

请记牢这三条，后面 19 章的每一个设计决策几乎都能回溯到它们：

1. **上下文贯穿全链路**：一个不可变的 `CodeTaskContext`（租户、仓库、用户、角色、traceId）随任务一路传递。任何一步都不能「丢租户」，也不能「改租户」。
2. **最小改动，而非重写**：让模型输出 SEARCH/REPLACE 补丁而不是整个文件——这既省 token，更重要的是**防止它顺手改掉无关代码**。
3. **验证闭环优先于生成能力**：一个能自我验证的平庸模型，工程价值远高于一个不会验证的天才模型。**没有验证的 Agent 不敢让它碰生产代码**。

### 2.3 本章的代码地基

Day17 的领域内核（`core`）已经就位，它零框架依赖，是所有模块最稳定的地基：

- [`CodeTaskContext`](../../../core/model/CodeTaskContext.java) —— 贯穿编码链路的不可变上下文；
- [`CodeResult`](../../../core/common/CodeResult.java) —— 统一返回，**失败时带可执行建议**；
- [`CodeException`](../../../core/common/CodeException.java) / [`CodeErrorCode`](../../../core/common/CodeErrorCode.java) —— 统一异常与错误码；
- 强类型 ID：`TenantId` / `RepoId` / `UserId` / `TaskId`。

---

## 三、怎么用（How）

> 技术栈：Java 21 + JDK 自带 `javax.tools`。本章代码不依赖数据库与外部模型，可独立编译运行。

### 3.1 贯穿全链路的不可变上下文

Code Agent 会在虚拟线程里并行跑多路检索与多个补丁候选。如果上下文可变，一个子任务改了 `repoId`，其余子任务就会**静默写到别人的仓库里**。所以它是 `record`：

```java
public record CodeTaskContext(
        TenantId tenantId,
        RepoId repoId,
        UserId userId,
        java.util.Set<String> roles,
        TaskId taskId,
        String branch,
        String traceId
) implements Serializable {

    public CodeTaskContext {
        CodeException.requireNonNull(tenantId, "tenantId");
        CodeException.requireNonNull(repoId, "repoId");
        CodeException.requireNonNull(userId, "userId");
        CodeException.requireNonNull(taskId, "taskId");
        roles = roles == null ? java.util.Set.of() : java.util.Set.copyOf(roles);
        if (branch == null || branch.isBlank()) {
            branch = "main";
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = "trace-" + UUID.randomUUID();
        }
    }

    /** 是否允许写仓库。默认拒绝：没有 CODE_WRITER 或 ADMIN 就只能只读分析。 */
    public boolean canWrite() {
        return roles.contains("CODE_WRITER") || roles.contains("ADMIN");
    }

    /** 是否允许在沙箱里执行命令。这是最危险的权限，必须显式授予。 */
    public boolean canExecute() {
        return roles.contains("CODE_EXECUTOR") || roles.contains("ADMIN");
    }

    /** 构造沙箱工作目录名：租户 + 仓库 + 任务 三级隔离。 */
    public String sandboxDirectoryName() {
        return tenantId.value() + "__" + repoId.value() + "__" + taskId.value();
    }

    /** 派生一个新上下文（不可变的正确姿势）。 */
    public CodeTaskContext withBranch(String newBranch) { ... }
}
```

三个细节值得品味：

- **`canWrite()` / `canExecute()` 默认拒绝**：没给角色就是 `false`。权限应当是「显式授予」而不是「显式禁止」。
- **`sandboxDirectoryName()` 三级隔离**：租户 + 仓库 + 任务。多租户共用一台机器时，这是防止「A 公司的 Agent 读到 B 公司源码」的第一道物理隔离。
- **`withBranch()` 派生而非修改**：需要变更时用新方法返回新实例，绝不原地修改。

在 Chapter 03 的 Demo 里，这个上下文被真实构造出来并打印（下面这段是 `repo.Chapter03Demo` 的**真实运行输出**）：

```
[上下文] CodeTaskContext{tenant=tenant-acme, repo=order-service, task=task-001, branch=main, trace=trace-6d1ebe31-672f-4486-86db-2b4357a11f2b}
[沙箱目录] tenant-acme__order-service__task-001
```

注意 `trace` 是自动生成的 UUID——它会在后续每一章的 Trace 里反复出现，把一次任务的全部节点串成一条线。

### 3.2 统一返回：失败要带「可执行建议」

Code Agent 的一次任务可能失败在任意环节（读文件、打补丁、编译、测试）。如果每层都抛异常给前端，前端就只能显示「500 Internal Server Error」。更糟的是，**大多数失败是用户可修正的**——所以 `CodeResult` 刻意带了 `hint` 字段：

```java
public record CodeResult<T>(
        boolean success,
        T data,
        String code,
        String message,
        String hint
) implements Serializable {

    public static <T> CodeResult<T> success(T data) { ... }

    public static <T> CodeResult<T> failure(CodeErrorCode code, String message) { ... }

    /** 带可执行建议的失败。 */
    public static <T> CodeResult<T> failure(CodeErrorCode code, String message, String hint) { ... }
}
```

对比一下两种失败信息的工程价值：

```
// 没有 hint
✗ 补丁未能应用

// 有 hint（Chapter 07 真实输出）
✗ 补丁未能应用：未匹配到 search 片段（精确匹配命中 0 处）
  建议：请重新读取文件当前内容，确认片段首行「public Money add(Money other) {」
        是否存在、缩进与空行是否一致；不要用记忆里的旧代码写补丁
```

后者不仅能让人看懂，**还能直接喂回给模型让它下一轮改正**——这正是自修复循环能工作的前提（Ch13）。

### 3.3 统一异常：只抛「不该发生」的事

```java
public class CodeException extends RuntimeException {
    private final CodeErrorCode errorCode;

    public static <T> T requireNonNull(T value, String name) { ... }
    public static String requireText(String value, String name) { ... }
    public static void requireTrue(boolean condition, CodeErrorCode code, String message) { ... }
}
```

这里有一条**贯穿全课程的铁律**，请务必区分：

| 情况 | 处理方式 | 例子 |
| --- | --- | --- |
| **可预期的失败** | **返回值**（`ToolResult` / `CodeResult` 的 failure） | 文件不存在、正则写错、编译报错、命令超时 |
| **不该发生的事** | 抛 `CodeException` | 参数为空、上下文缺租户、编译器不可用 |

理由很实在：**可预期的失败要参与流程**（编译错误要喂回模型重试），而异常会中断流程。把编译错误当异常抛出去，自修复循环就永远收不到反馈。

---

## 四、真实项目中的应用

在 ZeroHub 这样的企业平台里，Chapter 01 的地基会长成这样：

1. **入口层**：一个 `@RestController` 接收 `POST /api/v2/code/tasks`，从 JWT / 网关头里解析出租户、用户、角色，构造 `CodeTaskContext`。
2. **编排层**：`DefaultCodeAgentPipeline`（Ch20）按顺序编排上下文选择、生成、验证、评审、PR。
3. **返回层**：把 `CodeTaskResult` 包装进 `CodeTaskResponse`，统一携带 traceId 返回前端。

一次典型的企业请求：

```
API 网关（鉴权 / 限流）
   → Controller（从 Header 构造 CodeTaskContext）
      → Pipeline.execute(requirement, ctx)
         → 上下文选择 → 提示词 → 模型 → 解析 → 安全审查
            → 应用补丁 → 编译 → 测试 → 自修复 → 评审 → 变更集 → PR
      ← CodeTaskResult（status + 变更集 + PR + Trace）
   ← CodeTaskResponse（统一返回）
```

**关键点**：无论链路多长，`traceId` 始终不变。这让你可以把一次任务在 Grafana / OpenTelemetry 里串成一条 Trace——出问题时秒级定位是「哪一步坏了」，而不是对着一行「任务失败」猜半天。

---

## 五、避坑与优化

### 坑 1：把 CodeTaskContext 做成可变对象
**后果**：某个环节中途改了 `repoId`，后续步骤静默写到别人的仓库——这是多租户场景最严重的事故之一。
**做法**：必须是 `record`；需要变更时用 `withBranch()` 派生新实例。

### 坑 2：把所有失败都当异常抛
**后果**：编译错误被当成异常抛出，自修复循环收不到反馈，只能盲目重试或直接放弃。
**做法**：**可预期的失败走返回值**，异常只留给「不该发生的事」。这条规则后面每一章都会复现。

### 坑 3：失败信息不带建议
**后果**：前端显示「补丁应用失败」，用户不知道该怎么办，模型也不知道该怎么改。
**做法**：`CodeResult.hint` 必须写「下一步该做什么」，且这份 hint 要能直接喂回模型。

### 坑 4：把「能生成代码」当成「能交付改动」
**提醒**：本章讲的是地基，不是能力。一个只会生成代码文本的 Agent 在真实工程里几乎不可用——真正的门槛在验证、安全与可追溯上。

### 优化建议
- **上下文最小化**：`CodeTaskContext` 只放「贯穿全链路必需」的字段，业务参数走方法入参。
- **traceId 尽早生成**：在入口就生成并放进上下文，而不是等到编排层——否则入口到编排之间发生的事会失去追踪。

---

## 本章小结

- 企业需要的是**能交付改动的工程角色**，而不是「会写代码的对话框」；十个真实问题对应本课程的十个模块。
- Code Agent 的闭环是：读仓库 → 选址 → 生成补丁 → 安全审查 → 应用 → 编译 → 测试 → 自修复 → 评审 → 变更集 → PR。
- 三条贯穿原则：**上下文贯穿全链路、最小改动而非重写、验证闭环优先于生成能力**。
- 地基三件套：`CodeTaskContext`（不可变 + 默认拒绝权限 + 三级目录隔离）、`CodeResult`（失败带 hint）、`CodeException`（只抛不该发生的事）。

---

> **下一章预告**：Chapter 02 将展开 Code Agent 的**总体架构与模块划分**，讲清 core / repo / tools / analysis / context / patch / generator / sandbox / verify / iterate / safety / review / testgen / gitops / eval / observability / metering / orchestrator / api 这 19 个包的职责边界与严格单向的依赖方向。
>
> 👉 输入「继续」进入 Chapter 02。
