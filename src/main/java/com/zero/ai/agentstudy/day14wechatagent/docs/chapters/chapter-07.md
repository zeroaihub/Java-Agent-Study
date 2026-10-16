# 第七章 Workflow 与 Planning Agent：让 Agent 会规划、能干活

> 本章配套模块：`wechatworkflow`
> 关键词：Planning Agent、任务分解、Workflow 编排、状态机、黑板模式、失败重试、Human-in-the-Loop、审批挂起恢复

---

## 一、为什么学：一次对话背后，藏着一条流水线

先看一个真实场景。产品经理在企业微信里 @机器人：

> 「帮我整理今天所有会议内容，生成会议纪要，发送给项目组，同时把重要内容保存到知识库。」

如果你只用前六章的能力（消息路由 + 命令 + 会话 + 记忆 + RAG），会发现**它们都只能处理「一问一答」**。可上面这句话，本质是**一条流水线**：

```
读取会议内容 -> 生成纪要 -> 上传文件 -> 存入知识库 -> 发送给项目组
```

它有几个前六章无法优雅解决的难点：

1. **多步骤、有顺序**：纪要没生成，就没法上传、没法发送。步骤之间有数据依赖。
2. **中间要人把关**：上传文件、对外发送这类「有副作用」的操作，企业往往要求**人工审批**后才执行。
3. **某一步会失败**：调外部系统超时、网络抖动，需要**自动重试**，而不是整条链路直接崩。
4. **可能跑很久**：等审批可能等几小时。这期间**进程可能重启**，任务状态必须能持久化、能恢复。

一个只会「收到消息 -> 回一句」的 Agent，面对这种复合任务会当场懵掉。我们需要给它两样东西：

- **会规划（Planning）**：把一句话拆成一串有序步骤。这是 **Planning Agent**。
- **能干活（Workflow）**：按顺序推进步骤，管好每一步的成功/失败/跳过/审批。这是 **Workflow 编排引擎**。

> 一句话总结：**Planning Agent 负责「想」，Workflow 引擎负责「做」，本章教你把「想」和「做」拼成一条可靠的自动化流水线。**

这也正是本课程终极场景（第十章「会议纪要全链路」）的技术底座——本章把地基打好。

---

## 二、是什么：五个核心概念

### 2.1 Planning Agent（规划器）

Planning Agent 的职责只有一件事：**把自然语言目标分解为有序的、可执行的步骤列表**。

```
自然语言目标                          结构化步骤（有序）
"整理会议、生成纪要、          Planner       step1: office.summarize
 上传文件、存知识库、    ----------------->  step2: browser.upload   (需审批)
 发给项目组"                                 step3: knowledge.save
                                             step4: notify.send
```

注意：**规划器只「拆」，不「做」**。它输出计划，交给引擎执行。这就是「规划与执行分离」，好处是二者可以各自替换、各自测试。

- 教学期：用 `KeywordPlanner`（关键词规则分解），零依赖、结果可预测。
- 生产期：换成 `LlmPlanner`，让大模型用 function-calling 输出结构化步骤 JSON。

### 2.2 Workflow 状态机：双层状态

一条工作流有**两个层级的状态**，别混为一谈：

**步骤级状态 `StepStatus`（单个 Step 的生命周期）：**

```
PENDING ----> RUNNING ----> SUCCEEDED   (成功)
   ^             |     \---> FAILED      (失败且重试耗尽)
   |             |      \--> SKIPPED     (条件不满足，跳过)
   |             \--------> WAITING_APPROVAL  (需人工审批)
   |                              |
   \------------------------------/  (审批通过后 resetToPending 再跑)
```

- 终态：`SUCCEEDED` / `FAILED` / `SKIPPED`（不会再变）。
- 非终态：`PENDING` / `RUNNING` / `WAITING_APPROVAL`。

**任务级状态 `WorkflowStatus`（整个 Task 的宏观状态）：**

```
CREATED ---> RUNNING ---> COMPLETED   (所有步骤成功/跳过)
                |    \---> FAILED      (某关键步骤失败)
                \-------> SUSPENDED    (遇审批步骤，挂起等放行)
                              |
                              \--> RUNNING (放行后续跑)
```

任务状态由所有步骤状态**推导**而来（见 `WorkflowTask.refreshStatus`），不是手动乱设的：

```
任一步 FAILED           -> 任务 FAILED
存在 WAITING_APPROVAL   -> 任务 SUSPENDED
所有步都终态且无失败    -> 任务 COMPLETED
否则                    -> 任务 RUNNING
```

### 2.3 黑板模式（Blackboard）：步骤间怎么传数据

多步骤流水线里，前一步的产出常是后一步的输入。「生成纪要」产出的纪要文本，「上传文件」「发送消息」都要用。

我们不让步骤之间直接调用（那会耦合死），而是引入一块**共享黑板** `ExecutionContext`：

```
   +---------------------- ExecutionContext (黑板) ----------------------+
   |  meeting.summary = "# 会议纪要..."                                   |
   |  upload.fileUrl  = "https://docs.../xxx.md"                          |
   +---------------------------------------------------------------------+
        ^ write            ^ read/write           ^ read
        |                  |                       |
   [office.summarize] [browser.upload]        [notify.send]
```

每个步骤只跟黑板打交道：把产出写进去（key），需要时按 key 读出来。步骤之间**零直接依赖**，新增/删除步骤都不影响其他步骤。黑板用 `ConcurrentHashMap`，保证并行写入安全。

### 2.4 失败重试

外部调用天然不可靠。每个 `WorkflowStep` 带 `maxRetries`，引擎在步骤失败时判断 `canRetry()`：

```
执行步骤 -> FAILURE
              |
       canRetry() ? --是--> resetToPending (下一轮循环再试一次)
              |
              否 --> markFailed -> 任务 FAILED，中断
```

`attempts` 每次 `markRunning` 时 +1，`canRetry()` 即 `attempts <= maxRetries`。审批步骤 `maxRetries=0`（不重试）。

### 2.5 Human-in-the-Loop（人在环中）

对「有副作用」的关键步骤（上传、对外发送、扣款……），规划器把它标记为 `requiresApproval=true`。引擎遇到这类步骤时**不执行**，而是：

```
遇到 requiresApproval 步骤
   -> markWaitingApproval  (步骤 -> WAITING_APPROVAL)
   -> refreshStatus        (任务 -> SUSPENDED)
   -> save + return        (挂起，跳出执行循环)

           ... 等待人工在管理后台点「通过」...

approve(taskId, stepId)
   -> 该步 resetToPending
   -> run(task)            (从挂起点继续往下跑)
```

这就是企业级 Agent 与「玩具 Agent」的分水岭：**关键动作，人说了算。**

### 2.6 模块分层架构

```
wechatworkflow/
├── domain/
│   ├── model/                    领域模型（不依赖 Spring）
│   │   ├── StepStatus            步骤状态枚举
│   │   ├── WorkflowStatus        任务状态枚举
│   │   ├── ExecutionContext      黑板（共享上下文）
│   │   ├── StepResult            单步执行结果值对象
│   │   ├── WorkflowStep          步骤实体（可变）
│   │   └── WorkflowTask          聚合根
│   └── port/                     领域端口（接口）
│       ├── Planner               规划器端口
│       ├── StepExecutor          步骤执行器端口
│       └── WorkflowRepository    任务仓储端口
├── application/
│   └── WorkflowEngine            编排引擎（核心）
└── infrastructure/               适配器（实现端口）
    ├── InMemoryWorkflowRepository
    ├── KeywordPlanner            关键词规划器（Mock）
    └── executor/
        ├── OfficeSummarizeExecutor   office.summarize
        ├── BrowserUploadExecutor     browser.upload
        ├── KnowledgeSaveExecutor     knowledge.save
        └── NotifySendExecutor        notify.send
```

严格遵循六边形架构：领域层定义端口，基础设施层实现端口，应用层编排端口。**引擎不认识任何具体执行器**，只认 `StepExecutor` 接口——这是开闭原则的落地。

---

## 三、怎么用：核心代码逐段拆解

### 3.1 步骤实体 `WorkflowStep`：可变的状态载体

`WorkflowStep` 是本模块**唯一的可变实体**（其余都是不可变值对象/枚举）。为什么可变？因为它的状态要随执行推进而流转。

```java
public class WorkflowStep {
    private final String id;
    private final String name;
    private final String action;          // 供 StepExecutor 路由
    private final boolean requiresApproval;
    private final int maxRetries;

    private StepStatus status;             // 可变：状态
    private int attempts;                  // 可变：已尝试次数
    private String output;                 // 可变：产出
    private String error;                  // 可变：错误

    // 两个便捷工厂：区分「普通步骤」和「审批步骤」
    public static WorkflowStep of(String id, String name, String action) {
        return new WorkflowStep(id, name, action, false, 1);  // 普通步骤重试 1 次
    }
    public static WorkflowStep approvalStep(String id, String name, String action) {
        return new WorkflowStep(id, name, action, true, 0);   // 审批步骤不重试
    }

    public void markRunning() { this.status = StepStatus.RUNNING; this.attempts++; }
    public boolean canRetry() { return attempts <= maxRetries; }
}
```

**关键点：**
- `markRunning()` 里 `attempts++`，所以「尝试次数」精确等于「进入 RUNNING 的次数」。
- `canRetry()` 用 `attempts <= maxRetries`：第 1 次执行后 attempts=1，若 maxRetries=1 则 `1<=1` 为 true 还能再试一次，第 2 次后 attempts=2，`2<=1` 为 false 不再试。即**总共最多执行 maxRetries+1 次**。

### 3.2 聚合根 `WorkflowTask.refreshStatus`：状态推导规则

任务状态**不是手动设**的，而是每步之后由步骤状态**推导**出来。这保证了任务状态永远和步骤状态一致：

```java
public void refreshStatus() {
    if (status.isTerminal()) return;                 // 已终态，不再变

    boolean anyFailed = steps.stream()
            .anyMatch(s -> s.status() == StepStatus.FAILED);
    if (anyFailed) { this.status = WorkflowStatus.FAILED; return; }   // 任一失败 -> 整体失败

    if (hasWaitingApproval()) {                      // 有待审批 -> 挂起
        this.status = WorkflowStatus.SUSPENDED; return;
    }

    boolean allTerminal = steps.stream()
            .allMatch(s -> s.status().isTerminal()); // 全终态且无失败 -> 完成
    this.status = allTerminal ? WorkflowStatus.COMPLETED : WorkflowStatus.RUNNING;
}
```

优先级顺序很重要：**失败 > 挂起 > 完成 > 运行中**。只要有一步失败，整个任务就失败（fail-fast）；没失败但有待审批，就挂起。

### 3.3 黑板 `ExecutionContext`：步骤间共享数据

```java
public class ExecutionContext {
    private final String tenantId;        // 多租户隔离，贯穿全流程
    private final String conversationId;
    private final Map<String, Object> blackboard = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        if (key != null && value != null) blackboard.put(key, value);
    }
    public <T> Optional<T> get(String key, Class<T> type) {  // 类型安全读取
        Object v = blackboard.get(key);
        return type.isInstance(v) ? Optional.of(type.cast(v)) : Optional.empty();
    }
}
```

用 `ConcurrentHashMap` 而非普通 HashMap，为并行编排（扩展阅读的 DAG 场景）预留线程安全。`tenantId` 从头贯到尾，是多租户隔离的命脉。

### 3.4 规划器 `KeywordPlanner`：把一句话拆成步骤

```java
@Component
public class KeywordPlanner implements Planner {
    @Override
    public WorkflowTask plan(String goal, ExecutionContext context) {
        List<WorkflowStep> steps = new ArrayList<>();
        int seq = 1;
        if (containsAny(goal, "会议", "纪要", "整理", "总结")) {
            steps.add(WorkflowStep.of(stepId(seq++), "生成会议纪要", "office.summarize"));
        }
        if (containsAny(goal, "上传", "文件", "附件", "文档")) {
            // 上传属敏感操作 -> 标记为需审批
            steps.add(WorkflowStep.approvalStep(stepId(seq++), "上传文件(需审批)", "browser.upload"));
        }
        if (containsAny(goal, "知识库", "保存", "存档")) {
            steps.add(WorkflowStep.of(stepId(seq++), "保存到知识库", "knowledge.save"));
        }
        if (containsAny(goal, "发送", "通知", "发给", "推送")) {
            steps.add(WorkflowStep.of(stepId(seq++), "推送企业消息", "notify.send"));
        }
        if (steps.isEmpty()) {  // 兜底：不命中任何规则也不产出空任务
            steps.add(WorkflowStep.of(stepId(seq), "直接回复", "notify.send"));
        }
        return WorkflowTask.create(goal, context, steps);
    }
}
```

这是「Planning Agent」最朴素的实现。生产期换成 LLM 时，`plan()` 内部改为：构造 prompt -> 调 `ChatClient` function-calling -> 解析结构化步骤 -> 映射为 `WorkflowStep`。**接口不变，引擎无感知。**

### 3.5 执行器 `StepExecutor`：策略模式 + 黑板协作

每个执行器只认领一类 action，从黑板读输入、写输出：

```java
@Component
public class BrowserUploadExecutor implements StepExecutor {
    public static final String KEY_FILE_URL = "upload.fileUrl";

    @Override public boolean supports(String action) {
        return "browser.upload".equals(action);   // 只认领 browser.upload
    }

    @Override public StepResult execute(WorkflowStep step, ExecutionContext context) {
        // 读上一步产出
        String summary = context.get(OfficeSummarizeExecutor.KEY_SUMMARY)
                .map(String::valueOf).orElse("");
        if (summary.isBlank()) {
            return StepResult.failure("黑板缺少会议纪要内容，无法上传");
        }
        // 模拟上传，写产出回黑板
        String fileUrl = "https://docs.zerohub.example/meeting/" + context.conversationId() + ".md";
        context.put(KEY_FILE_URL, fileUrl);
        return StepResult.success("已上传会议纪要文件: " + fileUrl);
    }
}
```

注意执行器**用返回值 `StepResult` 而非抛异常**来表达成功/失败/跳过三态。为什么？因为引擎要**统一处理**三种结局，用返回值比满地 try-catch 清晰得多。异常仍会被引擎兜底捕获转成 failure。

### 3.6 编排引擎 `WorkflowEngine.run`：核心执行循环

这是整章的心脏：

```java
public WorkflowTask run(WorkflowTask task) {
    task.markRunning();
    int iterations = 0;
    while (iterations++ < MAX_STEP_ITERATIONS) {   // 上限防死循环
        Optional<WorkflowStep> next = task.nextPendingStep();
        if (next.isEmpty()) break;                 // 没有待执行步骤，收尾
        WorkflowStep step = next.get();

        if (step.requiresApproval()) {             // (1) 需审批 -> 挂起返回
            step.markWaitingApproval();
            task.refreshStatus();                  // 任务 -> SUSPENDED
            repository.save(task);
            return task;
        }

        executeStep(task, step);                   // (2) 普通步骤 -> 执行

        task.refreshStatus();                      // (3) 推进任务状态并存档
        repository.save(task);
        if (task.status().isTerminal()) break;     // 已完成/失败，跳出
    }
    task.refreshStatus();
    repository.save(task);
    return task;
}
```

**为什么用 `nextPendingStep()` 而不是 `for (step : steps)` 索引遍历？**
因为审批放行后要**从挂起点续跑**。用「找第一个 PENDING 步骤」的方式，天然支持「中断-恢复」：挂起时那一步变 WAITING_APPROVAL，放行后 resetToPending 变回 PENDING，再次 run 就会重新找到它继续。无需记录「上次跑到第几步」的游标。

`executeStep` 内部按结果分派：

```java
switch (result.outcome()) {
    case SUCCESS -> {
        step.markSucceeded(result.message());
        task.context().put(step.id(), result.message());  // 产出入黑板
    }
    case SKIP    -> step.markSkipped();
    case FAILURE -> handleFailure(task, step, result.message());  // 判重试
}
```

### 3.7 Human Approval：挂起与恢复

```java
public WorkflowTask approve(String taskId, String stepId) {
    WorkflowTask task = repository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + taskId));
    WorkflowStep step = task.findStep(stepId)
            .orElseThrow(() -> new IllegalArgumentException("步骤不存在: " + stepId));
    step.approve();            // 关键：置「已放行」标记并变回 PENDING
    return run(task);          // 从挂起点续跑
}
```

放行做两件事：给那一步打上「已审批放行」标记（`approved = true`）并 `resetToPending`，然后重新 `run`。引擎的 `nextPendingStep` 会再次找到它，但这次审批闸门的判断用的是 `step.isApprovalPending()`（`requiresApproval && !approved`），因为已放行，返回 `false`，于是不再挂起、直接执行——彻底解决了「审批放行后二次拦截」的问题。对应实体代码：

```java
// WorkflowStep
private boolean approved;                       // 审批放行标记

public void approve() {                         // 审批通过
    this.approved = true;
    this.status = StepStatus.PENDING;
}

public boolean isApprovalPending() {            // 引擎据此决定是否挂起
    return requiresApproval && !approved;       // 需审批 且 尚未放行 才拦截
}
```

驳回则 `markFailed`，任务随之 FAILED。这样审批的「放行」与「拦截」形成了闭环，既支持挂起等待，也保证放行后不重复卡住。

---

## 四、真实项目：会议纪要全链路旅程推演

理论讲完，我们把镜头拉到真实业务：一位项目经理在企业微信里对机器人发了一句话——

> **「整理今天会议内容，生成纪要，上传文件，保存知识库，发给项目组」**

这句话最终会驱动 `WorkflowEngine.planAndRun` 走完一条完整的自动化流水线。下面我们逐帧推演。

### 4.1 入口：一行代码启动整条流水线

```java
ExecutionContext ctx = new ExecutionContext("tenant-001", "user-pm-8848");
WorkflowTask task = workflowEngine.planAndRun(
        "整理今天会议内容，生成纪要，上传文件，保存知识库，发给项目组", ctx);
```

`planAndRun` = `plan` + `run`。第一步 `KeywordPlanner` 把这句话分解成 4 个有序步骤；第二步引擎顺序推进。

### 4.2 规划结果：KeywordPlanner 分解出的 4 步

| 序号 | 步骤名称 | action | 是否审批 | 命中关键词 |
|------|----------|--------|----------|------------|
| s1 | 生成会议纪要 | `office.summarize` | 否 | 会议 / 纪要 |
| s2 | 上传纪要文件 | `browser.upload` | **是** | 上传 / 文件 |
| s3 | 保存到知识库 | `knowledge.save` | 否 | 知识库 / 保存 |
| s4 | 发送给项目组 | `notify.send` | 否 | 发 / 项目组 |

注意 s2 是 `approvalStep`（`requiresApproval=true, maxRetries=0`）——上传对外文件是「不可逆的对外动作」，必须人工把关。

### 4.3 时序推演（ASCII 时序图）

```
PM ->> Engine : planAndRun("整理会议...")
Engine ->> Planner : plan(goal, ctx)
Planner -->> Engine : WorkflowTask[s1,s2,s3,s4]  (CREATED)

Engine ->> Engine : run(task)  ->  markRunning (RUNNING)
  loop 顺序推进
    Engine ->> s1 : nextPendingStep -> office.summarize
    s1 -->> Engine : SUCCESS  黑板["s1"]=纪要文本
    Engine ->> s2 : nextPendingStep -> browser.upload
    Note over s2 : isApprovalPending()=true
    Engine ->> s2 : markWaitingApproval
    Engine -->> PM : return task (SUSPENDED, 等待审批)
  end

PM ->> Engine : approve(taskId, "s2")     // 人工点了「同意」
Engine ->> s2 : approve()  approved=true, PENDING
Engine ->> Engine : run(task)  从挂起点续跑
  loop 续跑
    Engine ->> s2 : nextPendingStep -> browser.upload
    Note over s2 : isApprovalPending()=false (已放行)
    s2 -->> Engine : SUCCESS  黑板["upload.fileUrl"]=https://...
    Engine ->> s3 : knowledge.save
    s3 -->> Engine : SUCCESS  纪要切片入库
    Engine ->> s4 : notify.send
    s4 -->> Engine : SUCCESS  Markdown 消息已推送项目组
    Engine ->> Engine : nextPendingStep=empty -> break
  end
Engine ->> Engine : refreshStatus -> COMPLETED
Engine -->> PM : return task (COMPLETED)
```

### 4.4 黑板（ExecutionContext）演变表

黑板是这条链路的「共享记忆」，每步产出写进去，后续步骤读出来消费：

| 时刻 | 黑板内容（key -> value） | 谁写的 | 谁读它 |
|------|--------------------------|--------|--------|
| 初始 | `tenantId=tenant-001`, `userId=user-pm-8848` | 构造时 | 全体 |
| s1 后 | + `meeting.summary=「今日共 3 场会...」`<br>+ `s1=生成纪要成功` | OfficeSummarizeExecutor | s2/s3/s4 |
| s2 后 | + `upload.fileUrl=https://drive/.../纪要.md`<br>+ `s2=上传成功` | BrowserUploadExecutor | s3/s4 |
| s3 后 | + `s3=已切片入库` | KnowledgeSaveExecutor | s4 |
| s4 后 | + `s4=已推送项目组` | NotifySendExecutor | — |

可以看到：`office.summarize` 产出的 `meeting.summary` 一路被下游三步复用；若 s1 没产出纪要，`knowledge.save` 会 **SKIP**（条件跳过），体现「黑板缺料则降级」的健壮性。

### 4.5 两条异常支线

- **上传失败但可容忍**：`browser.upload` 若 `maxRetries>0` 会自动重试；但因它是审批步骤 `maxRetries=0`，失败即 FAILED，整个任务 FAILED——符合「关键动作不重试、直接暴露给人」的设计。
- **PM 驳回**：若 PM 点「拒绝」，`reject(taskId,"s2",原因)` 把 s2 `markFailed`，任务 FAILED，后续 s3/s4 不再执行——这正是 Human-in-the-Loop 的价值：把不可逆动作的最终裁决权交回人手。

这条链路正是**第十章终极场景**的骨架。第十章会把每个 Mock 执行器替换为真实实现（Office Agent 调 LLM 生成纪要、Browser Agent 用 Playwright 上传、Knowledge 走 pgvector、Notify 调企业微信 API），骨架不变，只换血肉。

---

## 五、避坑指南（生产级 ≥12 条）

Workflow/Planning Agent 是「看着简单、上生产全是坑」的典型。下面 12 条全部来自真实血泪，务必逐条对照。

### 坑 1：步骤无限重试拖垮系统

**现象**：某步骤永远失败（如下游服务挂了），若不设上限，引擎会无限 `resetToPending` 重试，CPU 打满、日志刷爆。

**根因**：把「重试」写成 `while(failed) retry` 而无计数。

**正解**：`WorkflowStep.maxRetries` 必须有硬上限，`canRetry()=attempts<=maxRetries`，总执行次数 = `maxRetries+1`。审批步骤 `maxRetries=0`（失败即暴露）。本项目已内置。

### 坑 2：黑板并发读写导致数据错乱

**现象**：并行执行多步时，`ExecutionContext` 用普通 `HashMap`，出现丢写、`ConcurrentModificationException`。

**正解**：黑板底层用 `ConcurrentHashMap`；若引入并行 DAG 执行，写入用 `merge/compute` 原子操作，避免「读-改-写」竞态。

### 坑 3：审批挂起后任务丢失

**现象**：任务挂起(SUSPENDED)等待审批，此时进程重启，内存里的任务没了，审批人点「同意」时找不到任务。

**根因**：`InMemoryWorkflowRepository` 只在内存。

**正解**：挂起态任务**必须持久化**到 PostgreSQL/Redis；`approve` 时从仓储 `findById` 重建。本项目内存实现仅供教学，生产必须替换。

### 坑 4：步骤循环依赖

**现象**：Planner 分解出 A 依赖 B、B 又依赖 A 的产出，黑板永远等不到料，任务卡死。

**正解**：规划阶段做 DAG 环检测（拓扑排序）；线性 Workflow 天然无环，但一旦引入依赖声明就要校验。

### 坑 5：执行器非幂等，重试产生重复副作用

**现象**：`notify.send` 第一次发成功但响应超时，引擎判失败重试，结果项目组收到两条一样的纪要。

**根因**：执行器有「对外副作用」却不幂等。

**正解**：对外动作带幂等键（如 `taskId+stepId` 作 dedupe key），下游据此去重；或先查「是否已执行」再执行。**重试的前提是幂等**。

### 坑 6：失败无补偿回滚（缺 Saga）

**现象**：s3 存知识库成功、s4 发送失败，任务 FAILED，但知识库里已经存了脏数据，无人清理。

**正解**：对有副作用的多步流程引入 **Saga 补偿**——每步定义 `compensate()`，失败时逆序回滚已成功步骤。本项目为教学未含补偿，生产需按业务决定。

### 坑 7：长任务超时无兜底

**现象**：某步调用 LLM 或 Browser 卡住 10 分钟，整个任务线程被占死。

**正解**：每步执行加超时（`CompletableFuture.orTimeout` / 虚拟线程 + 超时中断）；超时按 FAILURE 处理走重试/失败逻辑。

### 坑 8：内存仓储进程重启丢任务

**现象**：同坑 3 的泛化——不止审批态，所有 RUNNING 任务重启即丢。

**正解**：任务状态机每次流转后 `save`（本项目已在每步后 save）；仓储换持久化实现即可无缝恢复，因为状态全在 `WorkflowTask` 里。

### 坑 9：Planner（LLM）幻觉分解出不存在的 action

**现象**：换成 LLM Planner 后，它「创造」了一个 `email.send` action，但没有任何执行器 `supports` 它，步骤直接失败。

**正解**：①给 LLM 的 prompt 里限定「只能从以下 action 白名单选择」；②规划后校验每个 action 都有执行器认领，否则拒绝该计划并让 LLM 重规划。本项目 `findExecutor` 已对「无执行器」做了显式 `markFailed`。

### 坑 10：条件分支死循环

**现象**：某执行器逻辑写错，永远返回让步骤重回 PENDING，`nextPendingStep` 永远拿到同一步，死循环。

**正解**：`MAX_STEP_ITERATIONS=100` 硬熔断（本项目已内置），超限强制中断并告警。

### 坑 11：审批放行后二次拦截

**现象**：`approve` 后 `run`，`nextPendingStep` 又拿到审批步骤，`requiresApproval` 仍为 true，再次挂起，永远过不去。

**正解**：引入 `approved` 放行标记，拦截判断用 `isApprovalPending()=requiresApproval && !approved`。本项目已修复（见 3.7），**这正是本章代码相较初版的关键改进**。

### 坑 12：tenantId 未贯穿导致越权

**现象**：多租户下，A 租户的任务被 B 租户 `approve`，或黑板数据串租户。

**正解**：`ExecutionContext` 携带 `tenantId`，仓储查询、审批、执行器全链路校验租户一致；`approve/reject` 时校验 `task.tenantId == 当前操作人.tenantId`。本项目黑板已带 tenantId，生产需在 approve 入口加校验。

---

## 六、面试题精讲

### Q1：Workflow 编排与 Saga 模式有什么区别？各自适用什么场景？

**答**：二者都用于「多步骤流程编排」，但侧重点不同：

- **Workflow（编排引擎）**：关注「按顺序/条件推进步骤、驱动状态机、支持挂起恢复」，是**正向流程**的驱动器。本章的 `WorkflowEngine` 即是。
- **Saga**：关注「分布式事务的最终一致性」，核心是**补偿机制**——每步定义 `compensate()`，一旦某步失败，逆序回滚已成功步骤，把系统恢复到一致状态。

关系：Saga 是 Workflow 的一种「带补偿的特化」。当流程步骤有**不可回滚前的副作用**（扣款、发消息、写库）且要求最终一致时，就在 Workflow 之上叠加 Saga 补偿。纯查询型或可重放的流程则无需 Saga。生产级 Agent 通常「Workflow 编排 + 关键步骤 Saga 补偿」组合使用。

### Q2：Planning Agent 的原理是什么？它和 LLM function-calling 是什么关系？

**答**：Planning Agent 的核心是「**目标分解**」——把一个自然语言高层目标拆成一组有序、可执行的原子步骤（Plan）。实现路径有两类：

1. **规则/关键词规划**（本章 `KeywordPlanner`）：靠关键词映射到预定义 action，确定性强、无幻觉，但泛化差。
2. **LLM 规划**：把「目标 + 可用工具白名单」喂给 LLM，让它输出 JSON 步骤列表。这里就用到 **function-calling / tool-calling**——LLM 从工具白名单里挑选并编排调用顺序。

关系：function-calling 是 LLM 规划的**落地机制**，Planning 是它的**上层认知能力**。二者配合：Planner 用 LLM 出计划（选哪些 tool、什么顺序），Executor 用 function-calling 真正调用这些 tool。关键防坑是「用白名单约束 LLM，规划后校验 action 都有执行器认领」（见坑 9）。

### Q3：Human-in-the-Loop 的挂起-恢复该如何设计？状态放哪？

**答**：三个要点：

1. **挂起点识别**：步骤标记 `requiresApproval`，引擎推进到它时不执行、改标 `WAITING_APPROVAL`，任务转 `SUSPENDED`，`return` 让出线程。
2. **状态持久化**：挂起态任务**必须落库**（坑 3），因为审批可能几小时后才来，进程早重启了。恢复靠 `repository.findById` 重建整个 `WorkflowTask`（状态全在对象里）。
3. **恢复不二次拦截**：放行时打 `approved` 标记（坑 11），引擎用 `isApprovalPending()` 判断，已放行则直接执行。用「找第一个 PENDING」而非索引游标，天然支持从任意挂起点续跑��

### Q4：Workflow 状态机如何做到「进程重启后无损恢复」？

**答**：关键在于「**状态外置 + 幂等推进**」：

1. **状态外置**：任务的全部状态（每步 status/attempts/output、任务 status、黑板）都在 `WorkflowTask` 聚合根里，每次流转后 `save` 到持久层。不依赖任何内存变量或线程栈。
2. **可从任意点重放**：`run` 的核心是 `nextPendingStep`——它只看「当前哪些步骤还是 PENDING」，不关心之前怎么走到这的。所以重启后 `findById` 拿回任务再 `run`，会自动从第一个未完成步骤继续。
3. **幂等保证**：已 SUCCEEDED 的步骤不会被重跑（`nextPendingStep` 跳过它们），配合执行器幂等（坑 5），重启不会产生重复副作用。

这也是为什么本项目「每步后都 save」——它把内存实现和持久化实现之间的差异缩小到「换个仓储 bean」而已。

---

## 七、本章小结

| 维度 | 关键结论 |
|------|----------|
| 核心能力 | Planning（目标分解）+ Execution（顺序驱动状态机），二者严格分离 |
| 双层状态机 | 步骤级 StepStatus + 任务级 WorkflowStatus，refreshStatus 按「失败>挂起>完成>运行」推导 |
| 步骤间协作 | 黑板模式 ExecutionContext，前步产出为后步输入，零直接依赖 |
| 失败处理 | maxRetries 上限重试，审批步骤不重试，MAX_STEP_ITERATIONS 熔断死循环 |
| Human-in-Loop | 需审批步骤挂起 SUSPENDED，approve 打 approved 标记从挂起点续跑，reject 则 FAILED |
| 策略路由 | StepExecutor.supports(action) 认领动作，findExecutor 选第一个支持者（开闭原则） |
| 中断恢复 | 状态外置每步 save + nextPendingStep 找第一个 PENDING，重启无损恢复 |
| 架构分层 | 领域端口无 Spring 注解，实现在 infrastructure 层，六边形架构闭环 |

本章我们从零搭起了一个生产级 Workflow/Planning 引擎的骨架：12 个类打通「规划 → 编排 → 执行 → 审批 → 恢复」全链路，编译通过。它是第十章终极场景的地基。

---

## 八、扩展阅读

- **Spring Statemachine**：Spring 官方状态机框架，适合状态流转复杂、需要可视化状态图的场景，可对比本章手写状态机理解取舍。
- **Temporal / Cadence**：业界主流的持久化工作流引擎，把「状态外置 + 可重放」做到极致（Event Sourcing），生产级长任务编排首选。
- **Saga 模式**（Chris Richardson《Microservices Patterns》）：分布式事务补偿的权威讲解，对应本章坑 6。
- **并行 DAG 编排**：当步骤间是「有依赖的图」而非「线性链」时，需拓扑排序 + 并行调度，可研究 Airflow / Dagster 的调度模型。
- **LangGraph**：以图（graph）建模 Agent 工作流，节点即步骤、边即转移，是 LLM 时代 Planning Agent 的现代实现范式，与本章思想高度一致。
- **function-calling / tool-calling 规范**：OpenAI Compatible API 的工具调用协议，是 LLM Planner 落地的底层机制（对应 Q2）。

---

## 九、下章预告：第八章《通知中心与 Webhook》（wechatnotify）

本章 `notify.send` 只是一个 Mock 执行器，把消息「假装」推送出去。但在真实企业场景里，「怎么把结果可靠地送达到人」本身就是一门学问：

- **通知中心（Notification Center）**：统一的消息出口，支持企业微信、邮件、短信多渠道，按用户偏好路由、失败重投、限流削峰。
- **Webhook 接收与回调**：如何安全接收企业微信/第三方系统的回调，验签、幂等、异步处理。
- **企业消息推送**：Markdown 卡片、@提醒、群机器人、模板消息的差异与最佳实践。
- **与本章的衔接**：`notify.send` 执行器将从 Mock 升级为「调用通知中心」，而审批挂起时的「通知审批人」也依赖通知中心——Workflow 与 Notify 在此交汇。

下一章，我们把「消息可靠送达」这最后一公里打通。

---