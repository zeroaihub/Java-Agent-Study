# 第十章 终极场景：会议纪要全链路——所有能力的总合奏

> 本章配套模块：`wechatagent`（顶层编排层）
> 关键词：用例编排、Planning Agent、Multi-Agent 协作、Human Approval、端到端链路、六边形闭环

---

## 一、为什么学：把九件乐器合成一首交响乐

前九章，我们一件一件打磨乐器：

| 章 | 能力 | 在终极场景里扮演什么 |
| --- | --- | --- |
| 二 | 消息模型 | 承载用户那句指令 |
| 三 | 会话管理 | 记住「谁在跟我说话」，维持多轮上下文 |
| 四 | 消息路由 | 判断这条消息该交给谁处理 |
| 五 | 命令系统 | 识别显式指令 |
| 六 | Memory/RAG | 给 Agent 长期记忆与知识检索 |
| 七 | Workflow/Planning | 把一句话拆成有序步骤并逐步执行 |
| 八 | 通知中心 | 可靠地把结果送达项目组 |
| 九 | API/管理后台 | 让平台可被外部驱动、可被运维 |

每件乐器单独都能响，但**用户要的不是听九段独奏，而是一首完整的曲子**。本章就是那位站在台前的指挥——把所有能力编织成一条端到端的智能体流水线，真正回答开篇那个问题：

> 用户在企业微信里发一句「帮我整理今天所有会议内容，并生成会议纪要，发送给项目组，同时把重要内容保存到知识库」，平台如何自动地、可靠地、一步步把它做完？

这就是 `wechatagent` 模块的使命：**顶层用例编排（Use Case Orchestration）**。

> 一句话总结：**前九章造能力，第十章串流程。编排层不发明任何新能力，它只负责「按正确的顺序、在正确的时机，调用正确的能力」，把离散的模块变成一个会干活的智能体。**

---

## 二、是什么：用例编排层的三个核心认知

### 2.1 编排层是「用例的落点」，不是「能力的实现」

六边形架构里，应用层负责**用例编排**，领域层负责**领域规则**。终极场景「会议纪要全链路」本身就是一个**用例**，它横跨会话、工作流、通知多个模块——没有任何单一模块该为它负责，于是我们把它放进独立的 `wechatagent` 模块。

**编排层的铁律：只编排，不实现。**

```
      wechatagent (编排层 · 本章)
             |  只调用，不实现
   +---------+---------+---------+
   |         |         |         |
SessionService  WorkflowEngine  (通知经 Workflow 的 NotifySendExecutor 触发)
 (第三章)       (第七章)              (第八章)
```

编排器 `MeetingSummaryOrchestrator` 自己不知道「怎么生成纪要」「怎么发通知」——它只知道「先取会话，再规划执行，命中审批就挂起等人放行」。**具体怎么做，是被编排模块的事**。这让编排层极其稳定：底层实现怎么升级，编排流程都不用动。

### 2.2 Planning Agent 是全链路的大脑

用户只说了一句自然语言，凭什么平台知道要「生成纪要→上传→存知识库→发通知」这几步？答案是第七章的 **Planning Agent（规划器）**：

```
"帮我整理会议内容，生成纪要发给项目组，重要内容存知识库"
                    |
              KeywordPlanner.plan()   <- 教学版：关键词规则拆解
                    |                     生产版：LLM function-calling 拆解
                    v
   +----------------------------------------------------+
   | Step1  生成会议纪要   (action=office.summarize)      |
   | Step2  上传归档       (action=browser.upload)        |
   | Step3  存入知识库     (action=knowledge.save)        |
   | Step4  发送通知       (action=notify.send, 需审批)   |
   +----------------------------------------------------+
```

**规划与执行严格分离**：规划器只产出「要做哪些步骤」，不真正执行任何动作；执行由 `WorkflowEngine` 驱动，逐步路由到对应的 `StepExecutor`。这是 Planning Agent 的精髓——先想清楚整盘棋，再一步步落子。

### 2.3 Human Approval 是高影响动作的安全阀

「发送通知给整个项目组」是一个**高影响、不可撤销**的动作。全自动跑到这一步时，平台不会闷头就发，而是**挂起等人点一下「同意」**——这就是第七章埋下的 `requiresApproval` 审批机制在终极场景里的价值。

```
Step1 纪要 --SUCCESS--> Step2 上传 --SUCCESS--> Step3 存库 --SUCCESS-->
                                                                    |
                                                          Step4 发通知(需审批)
                                                                    |
                                                    引擎挂起 -> 任务 SUSPENDED
                                                                |
                                              人工 POST /approve 放行
                                                                    |
                                                    从挂起点续跑 -> 发通知 -> COMPLETED
```

**中断-恢复能力**从何而来？回顾第七章 `WorkflowEngine.run` 的设计——它用「取下一个 PENDING 步骤」而非 for 索引遍历，所以审批放行后能天然地**从挂起点续跑**，无需记录游标。编排层只需转调 `approve(taskId, stepId)`，剩下的续跑逻辑引擎全包了。

---

## 三、怎么用：逐段拆解编排器与入站适配器

### 3.1 顶层编排器 `MeetingSummaryOrchestrator`

编排器是本章的绝对主角。它坐落在 `wechatagent/application` 包下，是一个 `@Service`，只依赖两个前九章的服务：

```java
@Service
public class MeetingSummaryOrchestrator {

    private final SessionService sessionService;   // 第三章：会话
    private final WorkflowEngine workflowEngine;    // 第七章：规划+执行+审批

    public MeetingSummaryOrchestrator(SessionService sessionService, WorkflowEngine workflowEngine) {
        this.sessionService = sessionService;
        this.workflowEngine = workflowEngine;
    }
}
```

**为什么只有两个依赖？** 这是刻意的「依赖面最小化」设计。通知能力（第八章）并没有作为直接依赖注进来——因为它已经被 `WorkflowEngine` 内部的 `NotifySendExecutor` 编织进「发通知」这个步骤里了。编排器越瘦，越不容易因底层变化而破碎。**编排层的依赖越少，稳定性越高。**

#### 3.1.1 全链路入口 `handle`——三步走

```java
public AgentReply handle(String tenantId, String userId, String conversationId, String instruction) {
    // 1. 识别身份 + 取/建会话（多轮上下文的锚点）
    ConversationSession session = sessionService.getOrCreate(tenantId, ChannelType.WECOM, conversationId);
    sessionService.touch(session);
    log.info("[Agent] 收到指令 tenant={} user={} conv={} turn={} 指令={}",
            tenantId, userId, conversationId, session.getTurnCount(), instruction);

    // 2. 组装执行上下文（贯穿工作流的共享黑板）
    ExecutionContext context = new ExecutionContext(
            tenantId, ChannelType.WECOM.getCode(), conversationId, userId);

    // 3. Planning + 顺序执行（规划器拆步 -> 引擎逐步推进）
    WorkflowTask task = workflowEngine.planAndRun(instruction, context);

    return toReply(task);
}
```

**第一步——取/建会话。** `getOrCreate` 是幂等的：同一个 `(tenant, channel, conversation)` 三元组第一次调用会新建会话，之后调用返回同一个会话对象。`touch(session)` 更新最近活跃时间并累加轮次计数，这是维持多轮上下文、以及后续会话过期清扫的依据。注意渠道用的是 `ChannelType.WECOM`（企业微信的枚举值是 `WECOM`，不是 `WECHAT`）。

**第二步——组装黑板。** `ExecutionContext` 是**黑板模式（Blackboard Pattern）**的实现：一个贯穿整个工作流生命周期的共享数据空间。它的构造器接收四个元信息——`tenantId / channelType / conversationId / userId`——这些会随工作流一路传递，让每个步骤都知道「我在为谁、在哪个渠道、哪个会话里干活」。步骤间的产出（如「生成的纪要文本」）也会 `put` 进这个黑板，供下游步骤 `get`。

**第三步——规划并执行。** 一行 `planAndRun` 背后是「规划 + 执行」两个阶段：引擎先让 `Planner` 把自然语言指令拆成有序步骤，再逐步执行。这一步可能同步跑完（COMPLETED），也可能中途撞上审批步骤而挂起（SUSPENDED）——两种结局都交给 `toReply` 去翻译。

#### 3.1.2 状态翻译 `toReply`——把工作流状态变成人话

```java
private AgentReply toReply(WorkflowTask task) {
    WorkflowStatus status = task.status();

    // 命中审批挂起：返回待审批步骤 ID，引导人工放行
    if (status == WorkflowStatus.SUSPENDED) {
        Optional<WorkflowStep> waiting = task.firstWaitingApproval();
        String pendingStepId = waiting.map(WorkflowStep::id).orElse(null);
        String pendingStepName = waiting.map(WorkflowStep::name).orElse("未知步骤");
        return new AgentReply(task.taskId(), status.name(), pendingStepId,
                "任务已挂起，等待人工审批步骤：" + pendingStepName,
                stepDigest(task));
    }

    // 已完成 / 失败 / 处理中：给出终态摘要
    String message = switch (status) {
        case COMPLETED -> "会议纪要全链路已完成，通知已送达项目组。";
        case FAILED -> "任务执行失败，请到管理后台查看死信与详情。";
        default -> "任务处理中，当前状态：" + status.name();
    };
    return new AgentReply(task.taskId(), status.name(), null, message, stepDigest(task));
}
```

这个私有方法是编排器的「翻译官」。它把工作流引擎内部的 `WorkflowStatus` 枚举，翻译成对外的、人类可读的 `AgentReply`：

- **SUSPENDED（挂起）**：这是终极场景最关键的分支。`firstWaitingApproval()` 取出第一个正在等待审批的步骤，把它的 `id` 塞进 `pendingStepId` 字段返回——调用方（前端/机器人）拿到这个 ID，就能引导人去点「同意/驳回」。**挂起不是失败，而是「暂停，等你拍板」。**
- **COMPLETED / FAILED / 其他**：用 Java 21 的 `switch` 表达式做状态到文案的映射，`default` 兜底处理中间态。

`stepDigest(task)` 把每个步骤压缩成 `(stepId, name, status)` 三元组列表，让回复能展示「每一步走到哪了」，这对排障和用户感知都很重要。

#### 3.1.3 审批放行 `approve` / 驳回 `reject`——续跑与终止

```java
public AgentReply approve(String taskId, String stepId) {
    log.info("[Agent] 人工审批放行 taskId={} stepId={}", taskId, stepId);
    WorkflowTask task = workflowEngine.approve(taskId, stepId);   // 从挂起点续跑
    return toReply(task);
}

public AgentReply reject(String taskId, String stepId, String reason) {
    log.info("[Agent] 人工驳回 taskId={} stepId={} 原因={}", taskId, stepId, reason);
    WorkflowTask task = workflowEngine.reject(taskId, stepId, reason);  // 终止，进 FAILED
    return toReply(task);
}
```

两个方法都极薄——真正的续跑/终止逻辑全在 `WorkflowEngine` 里。`approve` 让引擎从挂起的审批步骤继续往下跑（可能一路跑到 COMPLETED，也可能撞上下一个审批步骤再次挂起）；`reject` 则把任务打入 FAILED。编排器在这里再次践行「只编排，不实现」——它连「怎么续跑」都不关心。

### 3.2 入站适配器 `AgentController`

`AgentController` 是六边形架构的**入站适配器（Driving Adapter）**，坐落在 `wechatagent/web` 包，负责把 HTTP 世界翻译成对编排器的调用。它同样零业务逻辑：

```java
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final MeetingSummaryOrchestrator orchestrator;

    public AgentController(MeetingSummaryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/instruct")
    public ApiResponse<MeetingSummaryOrchestrator.AgentReply> instruct(
            @Valid @RequestBody InstructRequest request) {
        MeetingSummaryOrchestrator.AgentReply reply = orchestrator.handle(
                request.tenantId(), request.userId(),
                request.conversationId(), request.instruction());
        return ApiResponse.ok(reply);
    }

    @PostMapping("/{taskId}/steps/{stepId}/approve")
    public ApiResponse<MeetingSummaryOrchestrator.AgentReply> approve(
            @PathVariable String taskId, @PathVariable String stepId) {
        return ApiResponse.ok(orchestrator.approve(taskId, stepId));
    }

    @PostMapping("/{taskId}/steps/{stepId}/reject")
    public ApiResponse<MeetingSummaryOrchestrator.AgentReply> reject(
            @PathVariable String taskId, @PathVariable String stepId,
            @Valid @RequestBody RejectRequest request) {
        return ApiResponse.ok(orchestrator.reject(taskId, stepId, request.reason()));
    }
}
```

三个接口精确对应终极场景的三个交互时刻：**下指令（instruct）→ 放行（approve）→ 驳回（reject）**。它复用第九章的 `ApiResponse` 统一响应体，校验交给 `@Valid` + Bean Validation，异常交给 `GlobalExceptionHandler` 兜底——**Controller 只做校验、翻译、编排调用三件事**。

> **为什么直接暴露 JSON 入口，而不是接企微回调？** 真实生产里，企微消息回调会先进 `WebhookController`（第九章）验签解密，再转成结构化指令调用编排器。本章为聚焦「业务编排」，特意开一个简化 JSON 入口，让你用 curl 就能触发全链路，无需搭建企微回调环境。

#### 3.2.1 用 curl 跑通全链路

**① 下达指令：**

```bash
curl -X POST http://localhost:8080/api/agent/instruct \
  -H 'Content-Type: application/json' \
  -d '{
    "tenantId": "acme",
    "userId": "u_1001",
    "conversationId": "group_dev",
    "instruction": "帮我整理今天所有会议内容，生成纪要并发给项目组，重要内容存知识库"
  }'
```

若流程命中「发通知」这个审批步骤，会返回类似：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "taskId": "task-abc123",
    "status": "SUSPENDED",
    "pendingStepId": "step-notify-04",
    "message": "任务已挂起，等待人工审批步骤：发送通知",
    "steps": [
      {"stepId": "step-summary-01", "name": "生成会议纪要", "status": "SUCCEEDED"},
      {"stepId": "step-upload-02",  "name": "上传归档",     "status": "SUCCEEDED"},
      {"stepId": "step-kb-03",      "name": "存入知识库",   "status": "SUCCEEDED"},
      {"stepId": "step-notify-04",  "name": "发送通知",     "status": "WAITING_APPROVAL"}
    ]
  }
}
```

**② 人工放行**（拿上一步的 `taskId` 和 `pendingStepId`）：

```bash
curl -X POST http://localhost:8080/api/agent/task-abc123/steps/step-notify-04/approve
```

引擎从挂起点续跑，跑完发通知步骤，返回 `status: "COMPLETED"`——通知已送达项目组，全链路闭环。

**③ 或者驳回：**

```bash
curl -X POST http://localhost:8080/api/agent/task-abc123/steps/step-notify-04/reject \
  -H 'Content-Type: application/json' \
  -d '{"reason": "纪要内容需要修改后再发"}'
```

任务进入 FAILED，通知不会发出。

---

## 四、真实项目：一次会议纪要的完整旅程

现在把镜头拉远，看这条指令从企业微信发出，到通知落在项目组群里的**完整旅程**——每一段路都踩在前九章的能力上：

```
                        [ 企业微信 · 项目群 ]
   用户: "帮我整理今天所有会议内容，生成纪要发项目组，重要内容存知识库"
                               |
                               v
             +-------------------------------------+
             |  WebhookController (第九章·入站)      |  验签 / 解密 / 转结构化指令
             +-------------------------------------+
                               |
                               v
             +-------------------------------------+
             |  AgentController.instruct (第十章)   |  @Valid 校验 -> 翻译 -> 编排
             +-------------------------------------+
                               |
                               v
   +===============================================================+
   |          MeetingSummaryOrchestrator.handle  (第十章总指挥)      |
   |                                                               |
   |  (1) SessionService.getOrCreate + touch   <- 第三章:识别+会话   |
   |  (2) new ExecutionContext(...)            <- 黑板:元信息贯穿    |
   |  (3) WorkflowEngine.planAndRun(...)       <- 第七章:规划+执行    |
   +===============================================================+
                               |
                               v
             +-------------------------------------+
             |  Planner.plan (第七章·大脑)          |  自然语言 -> 有序步骤
             +-------------------------------------+
                               |
        +----------+-----------+-----------+--------------+
        v          v           v           v              v
   +---------+ +---------+ +---------+ +-----------+  (每步路由到对应 StepExecutor)
   | Step1   | | Step2   | | Step3   | | Step4     |
   | 生成纪要 | | 上传归档 | | 存知识库 | | 发送通知   |
   | office  | | browser | | knowledge| | notify    |
   | .summar | | .upload | | .save   | | (需审批!) |
   +---------+ +---------+ +---------+ +-----------+
     SUCCESS     SUCCESS     SUCCESS         |
                                             v
                              +-----------------------------+
                              | 命中 requiresApproval        |
                              | -> 引擎挂起, 任务 SUSPENDED    |
                              | -> 返回 pendingStepId 给用户   |
                              +-----------------------------+
                                             |
              [ 项目负责人收到「待审批」提示，在后台/机器人点确认 ]
                                             |
                               POST /api/agent/{taskId}/steps/{stepId}/approve
                                             |
                                             v
                    +---------------------------------------+
                    | WorkflowEngine.approve -> 从挂起点续跑   |
                    | NotifySendExecutor -> NotificationCenter|  <- 第八章:可靠送达
                    +---------------------------------------+
                                             |
                                             v
                        [ 项目组群收到 Markdown 会议纪要通知 ]
                                             |
                        任务 COMPLETED，全链路闭环，管理后台可查全程
```

这张图把全书串了起来：

- **第九章**的 Webhook 是入口的门卫；
- **第十章**的编排器是站在门后的总指挥；
- **第三章**的会话让 Agent 认得人、记得上下文；
- **第七章**的 Planner 是把「一句话」变成「一盘棋」的大脑，Engine 是逐步落子的手；
- **第八章**的通知中心是最后可靠送达的邮差；
- **贯穿始终**的是 `ExecutionContext` 这块黑板和 `tenantId` 这条多租户隔离线。

**这就是企业级 AI Agent 的真正价值**：不是某个模型多聪明，而是能把一句模糊的人话，安全、可控、可观测、可人工干预地，落成一连串真实业务动作。**编排，才是商业价值的落点。**

---

## 五、避坑指南：编排层的 12 个致命陷阱

编排层站在所有模块之上，它的 bug 往往是「系统级」的。以下 12 条，条条都有血泪。

**1. 编排器里写业务逻辑，污染顶层。**
一旦你在 Orchestrator 里写下「如果纪要字数超过 500 就……」这类领域规则，编排层就腐化了。**编排器只回答「先做什么、再做什么、何时暂停」，永远不回答「怎么做」**。领域规则属于领域服务/领域模型。

**2. 跨模块循环依赖。**
`wechatagent` 依赖 workflow，若 workflow 反过来依赖 agent，就成了环。**编排层永远是「被依赖树的根」，只能它依赖别人，绝不能别人依赖它**。用 Spring Modulith 的模块校验或 ArchUnit 守住这条线。

**3. 审批挂起状态丢失。**
任务挂起后，状态若只存在内存，进程一重启，所有等待审批的任务就凭空消失，用户点「同意」时找不到任务。**挂起态必须持久化**（本教学版用内存仓储，生产版必须落 PostgreSQL），并保证 `taskId` 全局可寻址。

**4. 无幂等，重复触发。**
用户手抖点两下「发送」，或企微回调重试，编排入口若不幂等，就会跑两遍全链路、发两条通知。**入口要用 `(tenantId, conversationId, 指令指纹)` 或客户端幂等键去重**；发通知步骤本身也要靠第八章的 `dedupeKey` 兜底。

**5. 长链路无超时，线程被拖死。**
「上传归档」调外部系统、「生成纪要」调 LLM，任一环卡住而无超时，整条链路就挂起占着线程。**每个外部调用步骤都要设超时**，并考虑用虚拟线程承载阻塞等待，避免拖垮平台吞吐。

**6. 黑板数据类型混乱。**
`ExecutionContext` 是弱类型的 key-value 黑板，`put("summary", 对象)` 后下游 `get` 时强转类型写错就 `ClassCastException`。**约定并集中管理黑板的 key 常量与类型**，或用类型安全的读取方法，别让黑板变成「谁都能扔垃圾的垃圾桶」。

**7. 无补偿/回滚，半途失败留脏数据。**
「存知识库」成功了，但「发通知」失败，此时知识库已有数据、通知却没发——**长链路要有补偿思维**（Saga 模式）：要么定义每步的补偿动作，要么明确「哪些步骤失败可接受、哪些必须回滚」，别留下不一致的中间态。

**8. 多租户串号。**
`tenantId` 若在某一步丢失或写错，A 租户的会议纪要可能发到 B 租户的群里——这是**安全与合规的红线**。`tenantId` 必须从入口一路贯穿到每个步骤、每次仓储读写、每条通知，任何一环缺失都要在校验期拦截。

**9. 同步阻塞长链路，用户干等。**
`instruct` 若同步跑完整条链路才返回，用户要盯着转圈几十秒。**长链路应「快速受理、异步推进」**：入口先返回 `taskId` 与「处理中」，链路在后台推进，用户通过任务 ID 查询进展或等推送。

**10. 无可观测性，出事无法追踪。**
全链路跨会话、规划、执行、通知多个模块，若没有一条贯穿的 `traceId`，出问题时根本不知道卡在哪一步、为什么失败。**在 `ExecutionContext` 里携带 `traceId`，每步日志都带上它**，让一次请求在日志里可被完整串联（第九章的死信看板 + 这里的 traceId 是排障的左右手）。

**11. 把编排器做成上帝类。**
一个 Orchestrator 塞进「会议纪要 + 请假审批 + 报销 + 日报」几十个用例，几千行，谁都不敢动。**一个用例一个编排器**（或按用例分方法/分类），保持每个编排器聚焦单一业务流程。

**12. 直接依赖底层实现而非应用服务/端口。**
编排器若绕过 `SessionService` 直接 `new` 一个仓储、或直接调某个 Executor 的内部方法，就把自己焊死在了实现细节上。**编排器只依赖各模块对外的应用服务/引擎（用例边界）**，享受它们内部演进带来的红利而不受其牵连。

---

## 六、面试题：从「会编排」到「懂编排」

**Q1：为什么「会议纪要全链路」这个用例要单独放进 `wechatagent` 模块，而不是塞进 workflow 或 session？**

因为它是一个**横跨多个模块的用例（Use Case）**，不属于任何单一领域。会话模块只管会话、工作流模块只管步骤编排引擎、通知模块只管送达——谁都不该、也不能知道「一次完整的会议纪要业务流程」长什么样。把「业务用例编排」这个关注点独立成模块，符合六边形架构「应用层负责用例编排、领域层负责领域规则」的分工，也避免了让某个底层模块被业务流程细节污染。编排层是**被依赖树的根**，只依赖别人，不被别人依赖。

**Q2：编排（Orchestration）和编舞（Choreography）有什么区别？本章用的是哪种？为什么？**

- **编排（Orchestration）**：有一个中心指挥（本章的 Orchestrator + WorkflowEngine），它显式地知道整个流程，主动调用每一步。流程集中、可见、易追踪、易加人工审批，但中心节点是耦合焦点。
- **编舞（Choreography）**：没有中心，各服务通过事件互相触发（A 完成发事件，B 监听到就做自己的事）。去中心、松耦合、易扩展，但整体流程「散落在各处」，难追踪、难调试、难加全局审批。

本章用**编排**。因为会议纪要全链路需要**强顺序、可挂起审批、可观测、可人工干预**——这些正是编排的强项。编舞更适合高吞吐、弱顺序、天然事件驱动的场景（如订单状态广播）。企业级 Agent 的可控性要求，通常让编排成为首选。

**Q3：终极场景里的「Human Approval 挂起-放行-续跑」，技术上是怎么实现「续跑」的？**

关键在第七章 `WorkflowEngine.run` 的设计：它不是用 `for (i=0; i<steps; i++)` 的索引遍历，而是每次「取下一个 PENDING 状态的步骤」来执行。命中审批步骤时，把该步骤置为 `WAITING_APPROVAL`、任务置为 `SUSPENDED` 并返回，不再继续。人工 `approve` 后，把该步骤放行为 PENDING/SUCCEEDED，再次调用 run——引擎自然从「下一个 PENDING 步骤」接着跑，**无需记录任何游标或断点**。这就是「基于状态驱动」而非「基于位置驱动」的执行模型带来的天然中断-恢复能力。前提是：挂起态必须持久化，否则重启即丢。

**Q4：`ExecutionContext` 这块黑板贯穿全链路，它带来了什么好处，又埋了什么坑？如何治理？**

**好处**：① 元信息（tenantId/channel/conversation/user/traceId）一次组装、全程携带，每个步骤都拿得到，避免层层传参；② 步骤间产出通过黑板传递（上游 put、下游 get），解耦步骤间的直接依赖，新增步骤不改签名。

**坑**：① 弱类型 key-value，类型强转易错、key 拼写易错；② 变成「垃圾桶」，谁都往里塞，可读性下降；③ 隐式依赖——步骤 B 依赖步骤 A 写入的 key，但编译期看不出来。

**治理**：集中管理 key 常量与对应类型（如定义常量类 + 类型安全的 getter）；约定命名规范；关键依赖在步骤文档/契约里显式声明；必要时对黑板做「读多写少」的不可变快照，防止步骤间意外覆盖。

---

## 七、小结

| 关键点 | 一句话 |
| --- | --- |
| 编排层定位 | 用例的落点，只编排不实现，是被依赖树的根 |
| `MeetingSummaryOrchestrator` | 三步走：取会话 -> 组黑板 -> planAndRun；再把状态翻译成 AgentReply |
| Planning Agent | 规划与执行分离——先想清整盘棋，再逐步落子 |
| Human Approval | 高影响动作的安全阀：挂起(SUSPENDED) -> 放行(approve) -> 从挂起点续跑 |
| `ExecutionContext` | 黑板模式，携带元信息 + 步骤间产出，贯穿全链路 |
| `AgentController` | 入站适配器，三接口 instruct/approve/reject，零业务逻辑 |
| 依赖面最小化 | 只依赖 SessionService + WorkflowEngine，越瘦越稳 |
| 商业价值 | 不是模型多聪明，而是能把人话安全可控地落成业务动作 |

**本章我们做了什么？** 新建 `wechatagent` 顶层编排模块，用 `MeetingSummaryOrchestrator` 把前九章的会话、规划、执行、审批、通知能力编织成一条端到端的「会议纪要全链路」，并用 `AgentController` 暴露 curl 即可触发的入口，完整还原开篇的终极场景——包括最关键的 Human Approval 挂起-放行机制。

---

## 八、扩展阅读

- **Saga 模式**：长事务的补偿式一致性方案，理解链路中途失败如何回滚——Chris Richardson《微服务架构设计模式》第 4 章。
- **Orchestration vs Choreography**：分布式工作流的两种协调范式，本章 Q2 的深入版——Sam Newman《Building Microservices》相关章节。
- **工作流引擎**：Temporal / Cadence（持久化执行、天然支持长时挂起与续跑）、Camunda（BPMN 可视化编排、内建人工任务/审批），生产级会议纪要链路可考虑落地其上。
- **事件驱动架构（EDA）**：编舞范式的基石，理解何时该用事件替代中心编排。
- **Spring Modulith**：用模块边界校验守住「编排层不被反向依赖」的架构约束。
- **黑板模式（Blackboard Pattern）**：经典 AI 架构模式，多个知识源协作求解，`ExecutionContext` 即其轻量实现。
- **ArchUnit**：用测试代码守护架构规则（如禁止循环依赖、禁止编排器被下层依赖）。

---

## 九、全书总结与展望：从零件到智能体

十章走完，我们从一条消息的建模出发，一路造出了会话、路由、命令、记忆、规划、执行、通知、网关、后台，最后用一章把它们合奏成一个**能真正干活的企业级 AI WeChat Agent**。

回望这条学习路径，你收获的远不止「一个微信机器人」：

- **架构层面**：你完整实践了**六边形架构 + DDD + Spring Modulith** 的模块化设计——领域端口不依赖框架、入站/出站适配器双向隔离、应用层编排用例、领域层守护规则。这套心法适用于任何复杂后端系统。
- **AI Agent 层面**：你亲手实现了 Planning Agent（规划执行分离）、Multi-Agent 协作（多 Executor 分工）、Human-in-the-Loop（审批挂起续跑）、Memory/RAG、工具调用（MCP）——这些是当下所有生产级 Agent 平台的核心骨架。
- **工程层面**：多租户隔离、幂等、死信重投、统一响应、全局异常、可观测性、虚拟线程——这些让系统从「能跑」走向「敢上生产」。

**最重要的一课是本章点破的**：单个能力再强，不串成端到端的业务流程，就没有商业价值。**企业级 Agent 的真正门槛不是模型，而是编排**——把不确定的自然语言，安全、可控、可审计、可人工干预地，落成一连串确定的业务动作。

**接下来往哪走？** 把教学版的 Mock 实现逐个替换为生产实现：`KeywordPlanner` 换成 LLM function-calling 规划、内存仓储换成 PostgreSQL + pgvector、`office/browser/knowledge` 三个 Executor 接上真实的文档生成、Playwright 浏览器自动化与向量知识库、审批接入真实的企微审批流、全链路加上 traceId 与指标埋点、异步化长链路、接入真实企微回调……当你把这些一一点亮，这个 `day14-wechat-agent` 就从「教学项目」长成了可以支撑真实企业场景的 **ZeroHub AI Agent Platform** 的一块基石。

愿你带着这十章的心法，去构建属于你自己的、真正会干活的智能体。全书完。

---