# Chapter 20 · 编排与 API：把 19 章串成一条流水线

> 本章目标：把前 19 章的能力编排成 12 步流水线，暴露为 REST 接口，并通过 5 个真实场景验证「成功 / 被安全拒绝 / 权限不足 / 自修复救回 / 自修复救不回」五种结局。本章也是全课程的收束。

---

## 一、为什么要学（Why）

19 个模块各自都能跑，但它们需要被**按正确的顺序**串起来，而且这个顺序本身就是设计决策：

- **安全审查必须在应用补丁之前**（Ch14）：被拒的补丁不浪费编译测试资源；
- **编译必须在测试之前**（Ch11/12）：便宜的检查先做；
- **预算检查必须在每次模型调用之前**（Ch19）：事前而非事后。

除了顺序，编排层还要负责：

1. **统一的失败分类**：七种 `Status`，让调用方能程序化处理；
2. **Trace 贯穿**：每一步都埋点；
3. **权限前置校验**：不能写就直接拒，别浪费任何后续步骤。

---

## 二、是什么（What）

### 2.1 七种任务状态

| 状态 | 含义 | 可合并 |
| --- | --- | --- |
| `SUCCEEDED` | 编译 + 测试全通过 | ✓（且无 BLOCKER 评审意见） |
| `TEST_FAILED` | 编译过、测试没过 | ✗ |
| `COMPILE_FAILED` | 编译没过 | ✗ |
| `PATCH_FAILED` | 补丁一个都没落地 | ✗ |
| `REJECTED_BY_SAFETY` | 被安全策略拒绝 | ✗ |
| `BUDGET_EXCEEDED` | 被预算拦下 | ✗ |
| `UNPARSEABLE` | 模型输出无法解析 | ✗ |

**七种而非两种**，是因为它们的处理方式完全不同：`REJECTED_BY_SAFETY` 要转人工、`BUDGET_EXCEEDED` 要提醒用户加配额、`UNPARSEABLE` 可以自动重试一次。笼统报「失败」等于把判断成本推给调用方。

### 2.2 12 步流水线

```
[0] 权限校验（canWrite，否则 OPERATION_NOT_ALLOWED）
[1] 上下文选择        → Ch06
[2] 提示词装配        → Ch09
[3] 模型调用（预算）  → Ch09 + Ch19
[4] 补丁解析          → Ch08
[5] 安全审查（应用前）→ Ch14
[6] 补丁应用          → Ch07
[7] 编译 + 自修复     → Ch11 + Ch13
[8] 规则评审          → Ch15
[9] 变更集            → Ch17
[10] PR 草稿          → Ch17
[11] Trace + 预算结算 → Ch19
```

### 2.3 API 设计：租户从 Header 来

```java
public record CodeTaskRequest(
        String requirement,
        String branch,
        Integer maxRepairRounds,
        Boolean dryRun,
        String testClassName
) { }
```

**注意：请求体里没有 `tenantId` / `userId`。** 它们只能由网关 / JWT 解析后从 Header 注入：

```
POST /api/v2/code/tasks
X-Tenant-Id: tenant-acme
X-User-Id: zhangsan
X-Repo-Id: order-service
X-Roles: CODE_WRITER,CODE_EXECUTOR
```

理由是：**身份信息绝不能由客户端自己声明**。如果 Body 里能带 `tenantId`，那任何人都能伪造身份访问别人的仓库。

`maxRepairRounds` 还要夹到 `[0, 5]`——防止客户端传个 100 进来。

---

## 三、怎么用（How）

> 运行：`java -Dstdout.encoding=UTF-8 -cp target/classes com.zero.ai.agentstudy.day17codeagent.orchestrator.Chapter20Demo`

### 3.1 场景 1：正常任务

```
=== [1] 正常任务：给 Money 增加 multiply ===
  CodeTaskResult{task=task-4821, 状态=成功, 补丁 1 个, 变更 1 个文件, 可合并=true}
Trace trace-8b4c08cd-... / 任务 task-4821
  上下文选择          成功       81ms      0 token
  提示词装配          成功        1ms      0 token
  模型调用           成功        1ms   1514 token  model=stub-code-model-1.0
  安全审查           成功        0ms      0 token  1 个通过，0 个被拒
  补丁应用           成功        9ms      0 token  1/1
  编译+自修复         成功      477ms      0 token  初始状态即编译通过，无需修复（修复 0 轮）
  规则评审           成功        0ms      0 token  0 条意见
  变更集+PR         成功        0ms      0 token  ChangeBundle{文件 1 个, +4 -0, 整体风险=中}
  ------------------------------------------------
  合计 8 步, 1514 token, 步骤耗时 569ms（墙钟 661ms）
  瓶颈步骤：编译+自修复 (477ms)
  可合并 = true
```

8 步全成功，`可合并 = true`。注意「修复 0 轮」——**这个字段曾经被错误地实现成 `trace.steps().size()`，于是报出「修复 8 轮」**。真实轮次必须来自 `SelfRepairLoop` 的 `rounds()`，这是修掉的一个真实 bug。

### 3.2 场景 2：被安全策略拒绝

```
=== [2] 被安全策略拒绝：补丁想改 CI 配置 ===
  CodeTaskResult{task=task-5586, 状态=被安全策略拒绝, 补丁 0 个, 变更 0 个文件, 可合并=false}
  说明 = .github/workflows/ci.yml：禁止修改受保护路径：.github/workflows/ci.yml
  → 安全审查发生在「打补丁之前」，被拒后不会浪费任何编译与测试资源。
```

注意 `补丁 0 个`——**它根本没被打进去**。

### 3.3 场景 3：权限不足

```
=== [3] 权限不足：只读用户提交编码任务 ===
  ✓ 被拦截：当前用户 intern 没有写权限，无法执行编码任务
```

在流水线**第 0 步**就被拦下，一步都没往下走。

### 3.4 场景 4：自修复把它救回来

```
=== [4] 会犯错的模型：自修复循环把它救回来 ===
  CodeTaskResult{task=task-5686, 状态=成功, 补丁 1 个, 变更 1 个文件, 可合并=true}
    模型调用           成功        0ms   1431 token  model=repairable-model
    编译+自修复         成功      177ms      0 token  第 1 轮修复成功（修复 1 轮）
```

模型第一次漏了分号，自修复第 1 轮修好——**从「失败」变成「可合并」**。

**对照组**：

```
  【对照】模型每轮都给同一个全块替换补丁：
  CodeTaskResult{task=task-5954, 状态=编译未通过, 补丁 1 个, 变更 1 个文件, 可合并=false}
  → 自修复不是万能的。它只能修「能被一个补丁修正」的错误，
    救不了「模型根本没理解反馈」的情况——这正是第 13 章无进展检测要早停的原因。
```

**这个对照非常重要**：自修复有价值，但不是万能的。当模型根本没理解反馈时，继续重试只是烧钱——Ch13 的无进展检测正是为此存在。

### 3.5 场景 5：线上任务沉淀为评测样本

```
=== [5] 线上任务沉淀为评测样本 ===
  task-4821 stub-code-model-1.0 | 补丁 1/1 | 编译 通过 | 测试 通过 | 修复 0 轮 | 1514 token | 569ms
  → 把真实任务自动转成评测集，是持续提升 Agent 能力的唯一可行路径：
    人工标注太慢，而每一次真实任务本身就是一份标注数据。
```

`toTaskOutcome()` 把一次真实任务转成 Ch18 的评测样本——**形成闭环**：线上任务 → 评测样本 → 优化 → 更好的线上表现。

---

## 四、真实项目中的应用：生产化替换清单

| 层 | 课程实现 | 生产替换 |
| --- | --- | --- |
| 模型 | `StubCodeModel` | Spring AI `ChatClient` |
| 仓库 | `SampleRepository` 内存夹具 | JGit 拉真实分支 |
| 沙箱 | `ProcessSandbox`（进程级） | Docker / gVisor + 禁网 |
| 编译 | 全量 `javax.tools` | 增量编译 + 缓存 |
| 测试 | assert + main | JUnit 5 + Surefire |
| 静态分析 | 正则级解析器 | JavaParser / javac Trees API |
| 评审 | 纯规则 | 规则 + LLM 混合 |
| 持久化 | 全内存 | 任务表 / Trace 表 / 评测样本表（DATABASE.md） |

只需改 `api/CodeAgentConfiguration` 一处装配，其余 19 个包不动——**这就是 Ch02 端口—适配器架构的最终兑现**。

---

## 五、避坑与优化

### 坑 1：安全审查放在应用之后
**后果**：磁盘留下半成品，且浪费编译测试资源。
**做法**：审查在应用之前（本流水线第 5 步）。

### 坑 2：`repairRounds` 用 `trace.steps().size()` 冒充
**后果**：报「修复 8 轮」，Trace 与评测数据全部失真。
**做法**：轮次必须来自 `SelfRepairLoop.rounds()`（这是真实修掉的 bug）。

### 坑 3：身份信息从 Body 读取
**后果**：客户端可伪造 `tenantId` 访问别人仓库。
**做法**：只从 Header / JWT 注入，Body 里不设这些字段。

### 坑 4：`maxRepairRounds` 不夹紧
**后果**：客户端传 100，一个任务烧掉天量 token。
**做法**：夹到 `[0, 5]`，并再交给预算护栏兜底。

### 坑 5：不区分失败状态
**后果**：调用方无法程序化处理（该转人工还是该提醒加配额？）。
**做法**：七种 `Status`。

### 优化建议
- **异步化**：任务动辄数秒，同步 HTTP 容易超时；改 Worker + 任务查询接口（DATABASE.md 的 `code_task` 表）。
- **`dryRun` 要真正实现**：只生成补丁与 PR 草稿、不落盘，是让用户建立信任的好功能。

---

## 六、全课程回顾：20 章能力地图

| 层次 | 章节 | 解决的核心问题 |
| --- | --- | --- |
| **地基** | Ch01 架构 / Ch02 模块划分 | 分层、强类型、端口—适配器 |
| **理解** | Ch03 VFS / Ch04 工具 / Ch05 静态分析 / Ch06 上下文 | 读得懂、选得准 |
| **改动** | Ch07 补丁应用 / Ch08 补丁解析 / Ch09 生成 | 改得准、改得小 |
| **执行** | Ch10 沙箱 / Ch11 编译 / Ch12 测试 | 验得动 |
| **自愈** | Ch13 自修复 | 修得回、刹得住 |
| **守规** | Ch14 安全 / Ch15 评审 | 守得住 |
| **交付** | Ch16 测试生成 / Ch17 GitOps | 交得清 |
| **度量** | Ch18 评测 / Ch19 可观测与预算 | 好不好、值不值 |
| **集成** | Ch20 编排与 API | 串成线 |

四条贯穿全课程的架构原则：

1. **上下文贯穿全链路**：不可变 `CodeTaskContext`，权限默认拒绝，目录三级隔离。
2. **最小改动而非重写**：SEARCH/REPLACE 省 53.9% token，更防止顺手改无关代码。
3. **验证闭环优先于生成能力**：编译 + 测试 + 评审三道，缺一不可；**没有验证的 Agent 不敢碰生产代码**。
4. **失败要早、要准、要可执行**：安全审查在应用前、预算检查在调用前、失败信息带下一步建议。

---

## 本章小结

- 12 步流水线，两个关键顺序：**安全审查在应用之前**、**编译在测试之前**。
- 七种 `Status` 让调用方能程序化处理；`mergeable` = 成功且无 BLOCKER 评审意见。
- 身份只从 Header 注入，`maxRepairRounds` 夹到 `[0,5]`。
- 真实场景验证：成功可合并 / 被安全拒绝 / 权限不足 / **自修复救回** / **自修复救不回**（对照）。
- 线上任务自动转评测样本，形成「实践 → 评测 → 优化」闭环。
- 生产化只改 `api/CodeAgentConfiguration` 一处装配——端口—适配器架构的最终兑现。

---

> **全课程完结。** Day17 · Code Agent 的 20 章到此结束，配套文档见 [README.md](../README.md)、[ARCHITECTURE.md](../ARCHITECTURE.md)、[API.md](../API.md)、[DATABASE.md](../DATABASE.md)、[DEPLOY.md](../DEPLOY.md)、[SECURITY.md](../SECURITY.md)、[EVALUATION.md](../EVALUATION.md)。
