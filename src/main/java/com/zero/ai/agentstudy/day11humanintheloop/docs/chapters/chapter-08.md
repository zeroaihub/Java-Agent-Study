# Chapter 08 · Approval API + UI：把审批能力「暴露」出去，让人真正用起来

> 本章隶属 Day11 Human-in-the-loop 模块。前七章我们把审批引擎、中断/恢复、检查点、多级会签、反馈学习这些**内核能力**全都造好了。但有一个致命问题：这些能力此刻还全部锁在 Java 类里，只有写单元测试的你自己能调到。**审批人是人，不是 JVM。** 本章要做的，就是给这套内核套上一层「对外的皮」——一套 REST API + 一个审批控制台页面，让真正的业务人员能在浏览器里点「通过 / 拒绝 / 修改」，让 Agent 能通过 HTTP 提交审批。这是从「能力」到「产品」的最后一公里。

---

## 一、为什么要学（Why）

### 1.1 翻车现场：能力全都有，就是没人能用

某团队照着前七章的思路，把审批内核写得非常漂亮：状态机严谨、多级会签完备、反馈学习闭环都跑通了，单测覆盖率 95%。上线评审会上，产品经理问了一句：

> "那……运营同学在哪里点『通过』？"

全场沉默。因为所有能力都只暴露成了 Java 方法：`approvalEngine.approve(id, "alice", "同意")`。运营同学不会写 Java，Agent 侧的调用方是另一个 Python 服务，也拿不到这个 JVM 内的对象引用。

结果就是：**一套价值连城的内核，因为没有 API 和 UI，等于零。** 最后团队又花了一周补 Controller 和页面才真正上线——本可以在设计之初就规划好。

### 1.2 内核能力必须「被暴露」才有价值

一个残酷的工程现实：

| 状态 | 谁能用 | 业务价值 |
|------|--------|----------|
| 只有 Java 类 / 方法 | 只有同 JVM 内的代码 | 接近 0（无法交付给人和外部系统） |
| 暴露成 REST API | 任何能发 HTTP 的系统（Agent、Python、前端） | 高（跨语言、跨进程） |
| 再配一个 UI 控制台 | 不懂技术的业务人员 | 极高（人能直接操作） |

HITL 的核心矛盾是「机器要快、人要审」。而「人要审」这个动作，**发生在浏览器里，不发生在 JVM 里**。所以 API 层 + UI 层不是锦上添花，而是 HITL 闭环里不可或缺的一环。

### 1.3 本章要回答的三个核心问题

| 核心问题 | 对应组件 | 一句话 |
|----------|----------|--------|
| 内核能力怎么暴露成 HTTP 端点，又不把领域模型裸露出去？ | Controller + DTO 防腐层 | 薄 Controller + record DTO，隔离内外 |
| Spring 注解加在哪，才能不污染纯净的领域层？ | `HumanLoopConfig` 组装根 | 只在 API 层装配，领域层保持纯 POJO |
| 人怎么在浏览器里真正点「通过 / 拒绝」？ | 静态审批控制台 | 原生 HTML+JS，fetch 调 REST |

---

## 二、是什么（What）

### 2.1 分层全景：这一章新增了哪一层

回顾整个 Day11 模块的分层，本章新增的是最外面的**接入层（API Layer）**：

```
┌─────────────────────────────────────────┐
│  接入层 approvalapi（本章新增）           │
│  Controller + DTO + Config + 静态 UI      │  ← HTTP / 浏览器在这里进来
├─────────────────────────────────────────┤
│  用例层 approvalengine / multilevelapproval│
│  feedbackengine（前七章）                 │  ← 业务编排
├─────────────────────────────────────────┤
│  领域层 humancore（前七章）               │
│  纯 POJO：状态机 / 值对象 / 枚举          │  ← 无任何框架依赖
└─────────────────────────────────────────┘
```

关键原则：**依赖只能从外向内。** 接入层依赖用例层，用例层依赖领域层，反过来绝不允许——领域层永远不知道有 Spring、有 HTTP、有 Controller 的存在。这就是六边形架构（Hexagonal Architecture）的核心。

### 2.2 本章交付的文件清单

| 文件 | 角色 | 一句话职责 |
|------|------|-----------|
| `dto/SubmitApprovalRequest` | 入参 DTO | 承载「提交单级审批」的 HTTP 请求体 |
| `dto/SubmitMultiLevelRequest` | 入参 DTO | 承载「提交多级会签」的请求体（含审批链定义） |
| `dto/DecisionRequest` | 入参 DTO | 承载「通过/拒绝/修改」的决策请求体 |
| `dto/FeedbackRequest` | 入参 DTO | 承载「提交反馈」的请求体 |
| `dto/ApprovalView` | 出参 DTO | 读模型，把审批聚合根投影成扁平视图 |
| `config/HumanLoopConfig` | 组装根 | 集中装配所有 Bean（Composition Root） |
| `ApprovalController` | 控制器 | 单级审批的 8 个 REST 端点 |
| `MultiLevelApprovalController` | 控制器 | 多级会签的 4 个 REST 端点 |
| `FeedbackController` | 控制器 | 反馈收集与学习的 4 个 REST 端点 |
| `GlobalExceptionHandler` | 异常处理 | 把业务异常统一翻译成 HTTP 状态码 |
| `static/day11/approval-console.html/.js` | 前端 | 浏览器审批控制台 |

### 2.3 三个关键概念

**① DTO 防腐层（Anti-Corruption Layer）**

DTO = Data Transfer Object，数据传输对象。它是 API 层与领域层之间的一堵墙。为什么不直接把领域对象 `AgentAction` / `ApprovalRequest` 当作请求/响应体？三个理由：

- **解耦**：领域模型的字段是内部实现细节，一旦前端直接依赖，领域模型稍一重构就破坏 API 契约。DTO 是稳定对外契约，可独立演进。
- **安全**：领域对象带内部方法、审计字段，不该全部裸露。DTO 只暴露前端确实需要的字段。
- **校验**：DTO 是承接 HTTP 入参的第一道关，天然是做参数校验的位置。

本项目所有 DTO 都用 `record`：DTO 天然是「一组不可变的传输字段」，record 自动生成构造器、访问器、equals/hashCode，最贴合语义。

**② 读模型（Read Model）投影**

领域对象是为「写」而设计的（带状态机、审计链、受控变更方法），但前端「读」的时候只想要一坨拍平的、能直接渲染成表格的字段。硬把领域对象序列化给前端，会暴露内部方法、循环引用、懒加载等一堆麻烦。

解法：一个专门的视图 DTO `ApprovalView`，用静态工厂 `from(ApprovalRequest)` 把领域对象投影成扁平结构。读写分离，各自干净。

**③ 组装根（Composition Root）**

领域层和用例层都是纯 POJO，没有 `@Component`、没有 `@Service`。那 Spring 怎么创建它们、怎么注入依赖？答案是：在 API 层放一个 `@Configuration` 类 `HumanLoopConfig`，用一堆 `@Bean` 方法**手动 new 出所有对象并串好依赖**。这个集中装配的地方就叫「组装根」。好处是领域层保持纯净可移植，装配逻辑集中一处一目了然。

---

## 三、怎么用（How）

### 3.1 第一步：组装根 HumanLoopConfig

因为领域层是纯 POJO，我们在 `config/HumanLoopConfig` 里用 `@Bean` 把所有对象手动串起来。Spring 会按方法参数自动解析依赖顺序：

```java
@Configuration
public class HumanLoopConfig {

    @Bean
    public RiskPolicy riskPolicy() {
        return new DefaultRiskPolicy();
    }

    @Bean
    public ApprovalStateMachine approvalStateMachine() {
        return new ApprovalStateMachine();
    }

    @Bean
    public ApprovalRepository approvalRepository() {
        return new InMemoryApprovalRepository();
    }

    @Bean
    public ApprovalEngine approvalEngine(RiskPolicy riskPolicy,
                                         ApprovalStateMachine sm,
                                         ApprovalRepository repo) {
        return new DefaultApprovalEngine(riskPolicy, sm, repo);
    }

    @Bean
    public MultiLevelApprovalService multiLevelApprovalService(
            ApprovalStateMachine sm, ApprovalRepository repo) {
        return new MultiLevelApprovalService(sm, repo);
    }
    // ... feedbackRepository / feedbackEngine / feedbackLearningService 同理
}
```

**注意一个精妙点**：`approvalEngine` 和 `multiLevelApprovalService` 共享同一个 `approvalRepository` Bean（Spring 默认单例）。这意味着单级审批和多级会签**读写的是同一份存储**，所以审批控制台的 `/pending` 能同时看到两种来源的待办。

### 3.2 第二步：入参 DTO 防腐

以决策 DTO 为例，它同时服务于 approve / reject / modify / resubmit / abort 五个语义相近的端点：

```java
public record DecisionRequest(
        String operator,
        String comment,
        Map<String, Object> modifiedParams
) {
    /** 兜底：审批人为空时用 anonymous，避免审计链里出现 null。 */
    public String operatorOrAnonymous() {
        return (operator == null || operator.isBlank()) ? "anonymous" : operator;
    }
}
```

**设计取舍**：为什么一个 DTO 复用五个端点？因为它们的入参高度相似（都需要 operator + comment），差异靠**不同的 URL 路径**表达语义，而非不同的请求体结构。这样前端只需记一种请求体格式。

### 3.3 第三步：出参 DTO 读模型投影

`ApprovalView.from()` 是投影的核心——把「为写而生」的聚合根拍平成「为读而生」的视图：

```java
public static ApprovalView from(ApprovalRequest req) {
    List<DecisionView> decisions = req.getDecisions().stream()
            .map(DecisionView::from)   // 审计链也一并投影
            .toList();
    return new ApprovalView(
            req.getRequestId(),
            req.getAction().taskId(),
            req.getAction().type(),
            req.getAction().description(),
            req.getRiskLevel().name(),      // 枚举 → 字符串，前端好渲染
            req.getStatus().name(),
            req.getStatus().isTerminal(),   // 前端据此禁用按钮
            req.getRequiredLevels(),
            req.getApprovedLevels(),
            req.getCreatedAt(),
            req.getExpireAt(),
            decisions);
}
```

投影逻辑集中在这一处，Controller 只管调用 `ApprovalView.from(request)`，永远不用关心投影细节。

### 3.4 第四步：薄 Controller

Controller 只做三件事：**翻译 DTO → 委托 Service → 投影结果**。以单级审批的通过端点为例：

```java
@PostMapping("/{id}/approve")
public ApprovalView approve(@PathVariable("id") String id,
                     @RequestBody DecisionRequest body) {
    approvalEngine.approve(id, body.operatorOrAnonymous(), body.comment());
    return reload(id);   // 引擎只返回状态枚举，故回查完整详情再投影
}
```

**为什么变更后要 `reload`？** 引擎的动作方法只返回 `ApprovalStatus` 枚举（如 `APPROVED`），但前端需要完整最新详情（含审计链、已通过级数）。所以统一在动作后回查一次聚合根，再投影成 `ApprovalView` 返回。

### 3.5 第五步：全局异常处理

Controller 里不写一行 try/catch。领域层抛出的业务异常，交给 `@RestControllerAdvice` 集中翻译：

```java
@ExceptionHandler(IllegalArgumentException.class)   // 入参非法/资源不存在 → 400
public ResponseEntity<...> handleBadRequest(IllegalArgumentException ex) { ... }

@ExceptionHandler(IllegalStateException.class)      // 状态冲突/无权限 → 409
public ResponseEntity<...> handleConflict(IllegalStateException ex) { ... }
```

语义约定清晰：`IllegalArgumentException` = 你请求的东西不对（400）；`IllegalStateException` = 东西对但此刻不能这么做（409，冲突）。

### 3.6 第六步：静态审批控制台

`static/day11/approval-console.html` + `.js`，零依赖原生实现。核心就是 fetch 调 REST：

```javascript
async function loadPending() {
    const resp = await fetch('/day11/approvals/pending');
    const list = await resp.json();
    render(list);   // 渲染成审批卡片
}

async function decide(id, action) {   // action = approve/reject/modify
    const operator = prompt('审批人', 'alice');
    const comment  = prompt('审批意见', '同意');
    await fetch(`/day11/approvals/${id}/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ operator, comment })
    });
    loadPending();   // 刷新
}
```

启动应用后访问 `http://localhost:8080/day11/approval-console.html` 即可看到待办列表并操作。

---

## 四、用在哪（真实项目）

### 4.1 完整端点地图

本章暴露的三组 REST API：

**① 单级审批 `/day11/approvals`**

| 方法 | 路径 | 作用 |
|------|------|------|
| POST | `/day11/approvals` | 提交审批（Agent 侧调用） |
| GET | `/day11/approvals/{id}` | 查询单条详情 |
| GET | `/day11/approvals/pending` | 查询所有待办（控制台首页） |
| POST | `/day11/approvals/{id}/approve` | 通过 |
| POST | `/day11/approvals/{id}/reject` | 拒绝 |
| POST | `/day11/approvals/{id}/modify` | 改参后通过 |
| POST | `/day11/approvals/{id}/resubmit` | 被拒后重新提交 |
| POST | `/day11/approvals/{id}/abort` | 终止 |

**② 多级会签 `/day11/multi-approvals`**

| 方法 | 路径 | 作用 |
|------|------|------|
| POST | `/day11/multi-approvals` | 提交多级会签（带审批链定义） |
| POST | `/day11/multi-approvals/{id}/approve` | 审批当前级 |
| POST | `/day11/multi-approvals/{id}/reject` | 驳回（整体终态） |
| GET | `/day11/multi-approvals/{id}/current-level` | 查询当前轮到哪一级 |

**③ 反馈 `/day11/feedbacks`**

| 方法 | 路径 | 作用 |
|------|------|------|
| POST | `/day11/feedbacks` | 提交反馈（CORRECTION 触发增量学习） |
| GET | `/day11/feedbacks?taskId=xxx` | 查某任务的反馈 |
| GET | `/day11/feedbacks/stats` | 统计（好评率/均分） |
| GET | `/day11/feedbacks/few-shot?taskType=xxx` | 生成 few-shot 注入块 |

### 4.2 端到端 Demo：一次完整的审批流

```bash
# 1. Agent 提交一个高风险动作
curl -X POST localhost:8080/day11/approvals \
  -H 'Content-Type: application/json' \
  -d '{"taskId":"T-001","actionType":"DELETE_ORDER",
       "description":"批量删除 500 条测试订单","amount":null,
       "params":{"orderIds":["A","B"]}}'
# → 返回 {"requestId":"...","status":"PENDING","riskLevel":"HIGH",...}

# 2. 运营在控制台看到待办（或 curl）
curl localhost:8080/day11/approvals/pending

# 3. 运营点「通过」
curl -X POST localhost:8080/day11/approvals/{id}/approve \
  -H 'Content-Type: application/json' \
  -d '{"operator":"alice","comment":"确认是测试数据，同意"}'
# → 返回 {"status":"APPROVED", "decisions":[{审计链...}]}
```

这条流水线正是 Chapter 09「ERP 批量删单」实战的骨架。

### 4.3 三种典型接入方
- **Agent 服务**（可能是 Python）：通过 POST `/approvals` 提交动作、轮询 `/{id}` 等待结果。
- **业务运营人员**：打开审批控制台网页，点按钮操作。
- **上游系统**（如工单系统）：通过 GET `/pending` 拉取待办同步到自己的工作台。

三方全部通过 HTTP 契约通信，互不依赖对方的实现语言——这正是暴露 API 的价值。

---

## 五、避坑指南（≥10 条）

1. **别把领域对象直接当请求/响应体。** 直接 `@RequestBody AgentAction`、直接返回 `ApprovalRequest`，会让 API 契约与领域模型死死绑定，领域一重构 API 就崩。永远用 DTO 隔离。

2. **别在领域层加 Spring 注解。** 一旦领域类上出现 `@Service`/`@Component`，领域层就被框架污染，无法脱离 Spring 单测，也违背六边形架构。装配统一放组装根 `@Configuration`。

3. **别让 Controller 写业务逻辑。** Controller 只做「翻译 DTO→委托 Service→投影」。一旦 if/else 业务判断爬进 Controller，就变成了「胖 Controller」，无法复用、无法单测。

4. **动作后一定要回查（reload）。** 引擎方法只返回状态枚举。若直接把枚举返给前端，前端拿不到审计链和最新级数。必须回查聚合根再投影。

5. **异常语义别混用状态码。** 入参错/资源不存在用 400（IllegalArgumentException），状态冲突/无权限用 409（IllegalStateException）。别一律 500，也别一律 200 里塞 error 字段。

6. **兜底 null 入参。** operator/reviewer 为空时用 `anonymous` 兜底，否则审计链里会出现 null，排查时一脸懵。DTO 里提供 `operatorOrAnonymous()` 这类方法。

7. **多级会签的审批链必须由前端显式声明。** 单级审批可以由引擎推断风险，但「几级、每级谁批」是业务配置，Controller 无法发明，必须让调用方在请求体里传 `levels`。

8. **审批链的校验交给领域层，DTO 别重复校验。** 级号 1..N 连续、审批人非空、超时为正，这些校验 `ApprovalChain/ApprovalLevel` 的紧凑构造器已经做了。DTO 只做结构承载，避免校验逻辑两处维护。

9. **静态页面的 XSS 防护别忘。** 审批描述可能来自用户输入，直接 `innerHTML` 拼接会有 XSS 风险。`.js` 里用 `escapeHtml()` 转义尖括号引号后再渲染。

10. **前端解析错误体要防御。** 后端 4xx/5xx 返回的是 `{message}` 结构，前端 `data.message || resp.status` 兜底，别让 undefined 弹到用户脸上。

11. **单级与多级共享同一仓储要心里有数。** 二者的 `approvalRepository` 是同一个 Bean，`/pending` 会同时返回两类待办。这是特性不是 bug，但要清楚，否则会疑惑「怎么多了几条」。

12. **CORS 别裸奔上生产。** 本 Demo 前后端同源不涉及 CORS，但真实项目前端独立部署时，跨域配置要收紧到白名单域名，别图省事 `*` 全放开。

13. **别把内部堆栈抛给前端。** 兜底的 500 处理只回一句通用提示（`服务器内部错误：XxxException`），绝不把完整堆栈 `ex.getMessage()`/`printStackTrace` 序列化给外部，避免泄露内部结构。

---

## 六、小结

本章我们完成了从「内核」到「产品」的最后一公里：

- **接入层（approvalapi）**：Controller + DTO + Config + 静态 UI，把前七章的能力全部暴露成 HTTP。
- **DTO 防腐层**：用 record 隔离 API 与领域，解耦、安全、可校验。
- **读模型投影**：`ApprovalView.from()` 把聚合根拍平成前端好消费的视图。
- **组装根**：`HumanLoopConfig` 集中装配，领域层保持纯 POJO。
- **薄 Controller + 全局异常处理**：Controller 不写业务、不写 try/catch。
- **审批控制台**：原生 HTML+JS，让人真正能在浏览器点「通过 / 拒绝 / 修改」。

至此，一个业务人员能用、Agent 能调、外部系统能对接的完整审批服务已经就绪。下一章我们用它跑通「ERP 批量删单」的真实实战。

---

## 七、FAQ

**Q1：为什么不用 `@Service`/`@Component` 直接标注领域类，那样更省事？**
A：省事的代价是领域层被 Spring 绑架。领域层保持纯 POJO 后，可以在任何环境（无 Spring 的单测、其它框架、甚至换语言移植）复用。装配的「不省事」集中在组装根一处，是值得的取舍。

**Q2：一个 DecisionRequest 复用五个端点，会不会太粗？**
A：不会。这五个动作入参高度同构（operator + comment），语义差异由 URL 路径表达。若强行拆成五个 DTO，反而是过度设计。只有当某端点入参结构确实不同（如 modify 需要 modifiedParams）时，才在同一 DTO 里加可选字段。

**Q3：读模型 ApprovalView 和领域对象字段几乎一样，有必要单独建一个吗？**
A：有。「现在一样」不代表「永远一样」。领域对象会因内部需要新增字段/方法，而 API 契约需要稳定。二者解耦后各自演进，这层「冗余」是保险而非浪费。

**Q4：静态页面用原生 JS 会不会太简陋，该上 React 吗？**
A：教学场景刻意选原生，是为了让你看清「前端只是 fetch 调 REST」这个本质，不被框架细节淹没。真实项目当然可以上 React/Vue，但 API 契约完全不变——这也正说明前后端已经彻底解耦。

---

## 八、面试题

**1. 什么是 DTO 防腐层？为什么不能直接把领域对象暴露给前端？**
> DTO 是 API 层与领域层之间的传输对象，起隔离作用。直接暴露领域对象会导致：① API 契约与领域模型强绑定，领域重构即破坏契约；② 内部方法/审计字段裸露有安全风险；③ 缺少专门的入参校验位置。DTO 用 record 实现，稳定对外、可独立演进。

**2. 什么是组装根（Composition Root）？在六边形架构里它解决什么问题？**
> 组装根是集中创建对象、装配依赖的唯一位置（本项目的 `HumanLoopConfig`）。它解决的问题是：让领域层保持纯 POJO、不依赖任何 DI 框架，同时又能在应用启动时被正确装配。依赖注入的「脏活」全部收敛到最外层一处，内层保持纯净可移植。

**3. 「薄 Controller」原则是什么？违反它会有什么后果？**
> 薄 Controller 指 Controller 只负责「翻译 DTO → 委托 Service → 投影结果」，不写业务逻辑。违反后（业务判断爬进 Controller）会导致：逻辑无法复用（绑死在 HTTP 层）、无法脱离 Web 容器单测、职责膨胀成上帝类。

**4. 为什么 IllegalArgumentException 映射 400，IllegalStateException 映射 409？**
> 语义匹配 HTTP 状态码：IllegalArgumentException 表示「入参非法/资源不存在」，对应 400 Bad Request（客户端请求本身有问题）；IllegalStateException 表示「资源状态不允许该操作/无权限」，对应 409 Conflict（请求没错，但与当前状态冲突）。精准的状态码让调用方能据此做不同处理（400 改参重试 vs 409 换个状态再来）。

**5. 读模型（Read Model）投影解决了什么问题？和 CQRS 有什么关系？**
> 领域对象为「写」而设计（状态机、审计链、受控变更），前端「读」只想要扁平结构。读模型投影用专门的视图 DTO（`ApprovalView`）把聚合根拍平，避免暴露内部方法、循环引用、懒加载。这正是 CQRS（命令查询职责分离）思想的轻量体现：写模型和读模型分开，各自最优。