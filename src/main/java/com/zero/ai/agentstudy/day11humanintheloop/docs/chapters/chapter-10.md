# 第 10 章 企业审批中心 —— 全模块收官与避坑总结

> 本章是 Day 11「人机协同（Human-in-the-Loop）」的收官章。我们不再新增任何内核能力，
> 而是站在前九章之上做两件事：① 搭一块「企业审批中心」仪表盘，把散落的能力聚成一块
> 「一眼看全局」的运营屏；② 对整个 HITL 模块做一次系统性的架构回顾、避坑总结与生产化建议。

---

## 一、为什么要有「审批中心」这一章（Why）

### 1.1 一个真实的运营困境

前九章我们把能力一块块搭齐了：审批引擎、状态机、中断/恢复、检查点、任务修改、多级会签、
反馈学习、REST API + UI、ERP 删单实战。功能上，一个高危动作从「发起」到「批准/驳回」到
「执行」的闭环已经完全跑通。

但当这套系统真正上线后，运营团队第一天就会问你三个问题：

1. **「现在有多少审批在等人处理？」** —— 待办堆积是审批系统的头号健康指标。堆积说明审批人
   忙不过来，或者流程设计有问题，业务会被卡住。
2. **「最近驳回率、超时率是多少？」** —— 驳回率突然飙高，可能是 Agent 的规划质量下降；
   超时率高，说明审批人响应太慢，需要加人或调 SLA。
3. **「高风险的操作都走审批了吗，有没有漏网的？」** —— 按风险等级看分布，是合规审计最关心的。

这三个问题，前九章的任何一个端点都答不上来——因为它们各自只管自己那一小块（提交、审批、
执行）。**没有一个地方能「看全局」。** 这就是「审批中心」存在的理由。

### 1.2 三个核心问题

- **问题一：能力散落，没有聚合视角。** `/approvals/pending` 只能看待办，看不到已通过多少、
  驳回多少；`/erp/orders` 只能看订单，看不到审批全貌。运营需要一块把所有维度聚在一起的屏。
- **问题二：读写混在一起容易出乱子。** 如果统计逻辑硬塞进现有的写端点，一次「看报表」的请求
  就可能意外触发状态变更。读侧必须和写侧彻底分开。
- **问题三：一个模块交付了，却缺一份「怎么用、怎么防坑、怎么上生产」的总纲。** 前九章各讲一点，
  学员脑子里是碎片。收官章要把它拼成完整的图。

### 1.3 本章目标

- 用一个**纯只读**的审批中心，聚合全模块的运营视图（状态分布 / 风险分布 / 待办摘要）。
- 理解 **CQRS 读侧**的定位：读侧只聚合投影，对写模型零副作用。
- 对整个 Day 11 模块做架构回顾、14+ 条避坑总结、生产化落地建议。

---

## 二、审批中心是什么（What）

### 2.1 分层全景

审批中心是「读侧」的一薄层，坐在既有内核之上，不反向依赖任何业务：

```
                 ┌─────────────────────────────────────┐
   运营/管理员 ──▶│ ApprovalCenterController （只读门面）  │
                 │   GET /day11/approval-center/dashboard│
                 └───────────────┬─────────────────────┘
                                 │ 委托
                 ┌───────────────▼─────────────────────┐
                 │ ApprovalCenterService （CQRS 读侧）    │
                 │   遍历所有状态 → 聚合 → 投影            │
                 └───────────────┬─────────────────────┘
                                 │ 只读查询（findByStatus）
                 ┌───────────────▼─────────────────────┐
                 │ ApprovalRepository （既有出站端口）    │
                 │   InMemory → 生产可换 Redis+PostgreSQL │
                 └─────────────────────────────────────┘
```

依赖只向下、向内：读侧调仓储，仓储不知道读侧存在。删掉整个 `approvalcenter` 包，前九章
照样独立编译运行——这正是模块化的验证。

### 2.2 文件清单

| 文件 | 角色 | 职责 |
| --- | --- | --- |
| `approvalcenter/ApprovalDashboard.java` | 读模型 DTO（record） | 仪表盘快照：总数 / 各状态计数 / 风险分布 / 终态数 / 待办摘要 |
| `approvalcenter/ApprovalCenterService.java` | CQRS 读侧服务 | 遍历所有状态查询 → 聚合计数 → 投影成仪表盘 |
| `approvalcenter/ApprovalCenterController.java` | 只读 HTTP 门面 | 暴露 `GET /dashboard`，纯委托，无业务 |

### 2.3 三个关键概念

1. **CQRS 读侧（Command Query Responsibility Segregation）**：写模型（审批引擎）负责严格的
   状态流转，读模型（审批中心）只做聚合查询。两者分离，读侧可随意重算、缓存、并发访问，
   因为它对写侧毫无副作用。

2. **读模型投影（Read Model Projection）**：`ApprovalDashboard` 是专为「读」设计的扁平结构，
   和为「写」设计的领域聚合根 `ApprovalRequest` 完全解耦。前端拿到的是拍平的、能直接渲染
   成图表的数字，而不是带状态机的复杂对象。

3. **不改内核的聚合策略**：`ApprovalRepository` 故意没暴露「查全部」（生产里全表扫描危险）。
   审批中心通过「遍历所有 `ApprovalStatus` 分别 `findByStatus` 再合并」拿到全量——既复用既有
   接口，又把「全表扫描」的语义显式化，生产可无缝替换为带分页/时间窗的专用查询。

---

## 三、怎么用（How）

### 3.1 第一步：定义读模型 DTO —— `ApprovalDashboard`

读模型是专为「读」设计的扁平 record，字段就是前端图表要的那些数字：

```java
public record ApprovalDashboard(
        long total,           // 总数
        long pending,         // 待办（PENDING）
        long approved,        // 单级终态 / 多级中间态
        long finalApproved,   // 多级会签全过
        long rejected,        // 驳回
        long modified,        // 待重提
        long timeout,         // 超时
        long aborted,         // 终止
        long terminalCount,   // 已到终态总数
        Map<String, Long> statusBreakdown,   // 按状态名分组的原始计数
        Map<String, Long> riskBreakdown,     // 按风险等级分组
        List<PendingSummary> recentPending   // 最近待办摘要
) {
    public record PendingSummary(
            String requestId, String actionType, String description,
            String riskLevel, int requiredLevels, int approvedLevels) {}
}
```

**讲解：**
- 既给了**语义字段**（`pending`/`rejected` 等，前端直接绑数字卡片），又给了 `statusBreakdown`
  **原始 Map**（前端想自己画饼图时灵活取用）。两种粒度都留，是对前端友好的设计。
- `PendingSummary` 是嵌套 record——待办卡片只需要几个字段，不必把整个 `ApprovalRequest` 扔过去。
- 全 record、全不可变：读模型天然只读，用 record 表达再合适不过。

### 3.2 第二步：CQRS 读侧服务 —— `ApprovalCenterService`

核心是 `buildDashboard()`，四步走：查全量 → 按状态计数 → 按风险计数 → 挑待办摘要。

```java
@Service
public class ApprovalCenterService {

    private static final int MAX_RECENT_PENDING = 20;
    private final ApprovalRepository approvalRepository;

    public ApprovalCenterService(ApprovalRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    public ApprovalDashboard buildDashboard() {
        List<ApprovalRequest> all = loadAll();

        // ① 按状态计数：先给每个已知状态预置 0，保证维度完整
        Map<String, Long> statusBreakdown = new LinkedHashMap<>();
        for (ApprovalStatus s : ApprovalStatus.values()) {
            statusBreakdown.put(s.name(), 0L);
        }
        for (ApprovalRequest r : all) {
            statusBreakdown.merge(r.getStatus().name(), 1L, Long::sum);
        }

        // ② 按风险等级计数
        Map<String, Long> riskBreakdown = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getRiskLevel().name(), Collectors.counting()));

        // ③ 终态总数
        long terminalCount = all.stream()
                .filter(r -> r.getStatus().isTerminal()).count();

        // ④ 最近待办摘要（按发起时间倒序，最多 20 条）
        List<ApprovalDashboard.PendingSummary> recentPending = all.stream()
                .filter(r -> r.getStatus() == ApprovalStatus.PENDING)
                .sorted(Comparator.comparing(ApprovalRequest::getCreatedAt).reversed())
                .limit(MAX_RECENT_PENDING)
                .map(this::toPendingSummary)
                .toList();

        return new ApprovalDashboard(all.size(), /* ...各状态取值... */,
                terminalCount, statusBreakdown, riskBreakdown, recentPending);
    }

    // 遍历所有状态合并，把「全表扫描」拆成「逐状态查询」，复用既有接口
    private List<ApprovalRequest> loadAll() {
        List<ApprovalRequest> all = new ArrayList<>();
        for (ApprovalStatus s : ApprovalStatus.values()) {
            all.addAll(approvalRepository.findByStatus(s));
        }
        return all;
    }
}
```

**讲解四个关键点：**
- **状态维度预置 0**：先把 7 个状态都填 0 再累加。否则某状态计数为 0 时 Map 里根本没这个键，
  前端拿不到「REJECTED: 0」，图表就缺一根柱子。**「零也是一种数据」**，别让它消失。
- **`merge(key, 1L, Long::sum)`**：并发安全、语义清晰的计数惯用法，比「取出→加一→放回」优雅。
- **`loadAll()` 复用既有接口**：不给仓储加 `findAll()`，用遍历状态合并的方式——不改内核，
  又显式表达「这是全表扫描」的语义。生产环境这里直接换成一条带过滤条件的 SQL 即可。
- **待办摘要限流 20 条**：首页只展示最近 20 条待办，避免请求量大时把响应撑爆。看全部走分页端点。

### 3.3 第三步：只读 HTTP 门面 —— `ApprovalCenterController`

薄到极致——一个 GET，一行委托：

```java
@RestController
@RequestMapping("/day11/approval-center")
public class ApprovalCenterController {

    private final ApprovalCenterService approvalCenterService;

    public ApprovalCenterController(ApprovalCenterService s) {
        this.approvalCenterService = s;
    }

    @GetMapping("/dashboard")
    public ApprovalDashboard dashboard() {
        return approvalCenterService.buildDashboard();
    }
}
```

**讲解：**
- **只有一个 GET**：审批中心是读侧聚合，所有「写」操作（提交/审批/驳回/执行）都在前九章
  各自的 Controller 里各司其职。收官章不再造轮子，只聚一块「看全局」的屏。
- **无异常处理**：纯读、无入参校验，天然不会抛业务异常。真有意外由全局处理器兜底。
- **`@Service` 自动装配**：`ApprovalCenterService` 靠组件扫描注入既有的 `ApprovalRepository`
  Bean（`HumanLoopConfig` 已提供），无需在组装根里手写 `@Bean`。

---

## 四、用在哪：全模块端到端与架构回顾（真实项目）

### 4.1 审批中心端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/day11/approval-center/dashboard` | 审批中心总览：状态分布 / 风险分布 / 终态数 / 最近待办摘要 |

### 4.2 端到端演示：从空仪表盘到有数据

结合第 8、9 章的写端点，走一遍完整运营视角：

```bash
# 1) 一开始，仪表盘空空如也
curl http://localhost:8080/day11/approval-center/dashboard
# → {"total":0,"pending":0,...,"recentPending":[]}

# 2) 通过 ERP 实战发起一次删单审批（第 9 章）
curl -X POST http://localhost:8080/day11/erp/clean-test-orders
# → 返回一个 PENDING 的审批请求，记下 requestId

# 3) 再看仪表盘：pending 从 0 变 1，recentPending 里出现了这条
curl http://localhost:8080/day11/approval-center/dashboard
# → {"total":1,"pending":1,"riskBreakdown":{"HIGH":1},"recentPending":[{...}]}

# 4) 审批人批准（复用第 8 章通用端点）——两级会签，第一级过
curl -X POST http://localhost:8080/day11/approvals/{id}/approve \
  -H "Content-Type: application/json" \
  -d '{"operator":"alice","comment":"一级通过"}'

# 5) 第二级也批准
curl -X POST http://localhost:8080/day11/approvals/{id}/approve \
  -H "Content-Type: application/json" \
  -d '{"operator":"bob","comment":"二级通过"}'

# 6) 仪表盘更新：pending 归 0，finalApproved 变 1，terminalCount 变 1
curl http://localhost:8080/day11/approval-center/dashboard
# → {"total":1,"pending":0,"finalApproved":1,"terminalCount":1,...}

# 7) 执行删除（第 9 章）
curl -X POST http://localhost:8080/day11/erp/requests/{id}/execute
# → 订单被软删除，删单闭环完成
```

**一块屏，看清全模块的运营状态。** 这就是审批中心的价值。

### 4.3 全模块架构回顾（Day 11 十章串讲）

| 章 | 主题 | 核心能力 | 关键设计 |
| --- | --- | --- | --- |
| 01 | HITL 概览 | 为什么 Agent 需要人类闸门 | 高危动作必须「先停下来等人」 |
| 02 | 审批引擎骨架 | AgentAction / ApprovalRequest / 状态 | DDD 值对象 + 聚合根 |
| 03 | 中断与恢复 | Interrupt / Resume | 暂停点 + 恢复上下文 |
| 04 | 检查点 Checkpoint | 状态快照与回滚 | 可恢复执行 |
| 05 | 任务修改 Modify | 人工改参后重提交 | MODIFIED → resubmit → PENDING |
| 06 | 多级会签 | Multi-Level Approval | 逐级推进 + 会签终态 FINAL_APPROVED |
| 07 | 反馈与学习 | Human Feedback + Learning | 反馈沉淀，优化后续规划 |
| 08 | Approval API + UI | REST 门面 + 读模型投影 | 薄 Controller + ApprovalView |
| 09 | ERP 删单实战 | 三段式执行流端到端 | 唯一数据入口 + 状态守卫 |
| 10 | 企业审批中心 | 只读运营仪表盘 + 收官 | CQRS 读侧 + 全模块回顾 |

**贯穿全程的三条主线：**
1. **人类闸门不可绕过**：从状态机到唯一数据入口再到状态守卫，「没批就动不了」是代码结构强制的，
   不是流程约定。
2. **读写分离**：写侧（引擎/状态机）管流转，读侧（ApprovalView/Dashboard）管投影，互不污染。
3. **模块化可插拔**：内核不依赖任何业务实战包；删掉 `erpdemo`、`approvalcenter`，内核照样跑。
   换个业务名词就能复用整套 HITL 能力——这是把 HITL 做成独立模块的终极红利。

### 4.4 三种落地场景

- **运维审批中心**：批量重启、清库、扩缩容全走 HITL，仪表盘盯待办堆积与超时。
- **金融风控**：大额转账、退款、开户走多级会签，仪表盘按风险等级做合规审计。
- **内容/运营后台**：批量下架、封号、推送走审批，仪表盘看驳回率评估机器规划质量。

---

## 五、避坑指南 + 全模块小结 + FAQ + 面试题

### 5.1 本章避坑（审批中心）

1. **审批中心必须纯只读，绝不触发任何状态变更。** 一旦统计逻辑里混进了 `save`/`approve`，
   一次「看报表」就可能改数据。读侧的铁律是零副作用。

2. **状态维度要预置 0，别让「计数为 0 的状态」从结果里消失。** 前端图表需要完整的状态维度，
   `REJECTED: 0` 也是有意义的数据，缺了就少一根柱子。

3. **待办摘要要限流，别一次性把几万条待办全序列化返回。** 首页只展示最近 N 条，看全部走分页。
   仪表盘接口被打爆，往往就是因为没限流。

4. **不要为了「查全部」在仓储接口上加 `findAll()`。** 全表扫描在生产是危险操作，不该轻易暴露成
   通用能力。用遍历状态合并的方式显式表达语义，生产再换成带条件的专用查询。

5. **仪表盘是实时聚合还是缓存，要按量权衡。** 教学内存实现每次重算无所谓；生产数据量大时，
   要么加短 TTL 缓存，要么改成事件驱动的预聚合，别让每次刷新都全表扫描。

### 5.2 全模块避坑总纲（Day 11 精华 15 条）

1. **高危动作类型必须落在风险策略的高危集合里**，否则风险被判 NONE 直接放行——最隐蔽的审批失效。
2. **执行前回查最新状态，绝不信内存旧快照**：审批异步，提交与执行可能隔几小时。
3. **删/改的是「审批时那份清单」，不是「执行时重扫的清单」**，否则越权操作审批范围外的对象。
4. **唯一数据变更入口 + 入口处状态守卫**：让「没批就动不了」由代码结构强制，而非流程约定。
5. **状态白名单精确到「通过终态」**：多级会签下 APPROVED 只是中间态，FINAL_APPROVED 才是全过。
6. **用软删除而非物理删除**：审批链/审计引用不悬空，合规可追溯可恢复。
7. **所有状态流转必须过状态机校验**：非法流转直接抛异常，从根上杜绝脏状态。
8. **Controller 要薄**：只做 DTO 翻译、委托、投影，业务全在下层。
9. **异常交给全局处理器**：IllegalArgument→400/IllegalState→409/其他→500，别到处 try/catch。
10. **读写分离**：写模型管流转，读模型（View/Dashboard）管投影，各自干净。
11. **内核不依赖任何业务实战包**：删掉 erpdemo/approvalcenter，内核照样独立编译运行。
12. **复用通用审批端点**：业务接入方只是普通 AgentAction，别重复造 approve/reject——开闭原则。
13. **别制造空审批**：没有可操作对象就抛异常，别提交「操作 0 个对象」的请求消耗审批人注意力。
14. **待操作清单作为「证据」进审批**：让审批人看到具体删/改什么，而非模糊描述，避免盲批。
15. **教学用内存实现绝不能上生产**：审批记录必须持久化，重启即丢是致命的。

### 5.3 全模块小结

Day 11 我们从零搭起了一套**企业级、可插拔、可商业落地的 Human-in-the-Loop 模块**：

- **内核**：审批引擎 + 状态机 + 风险策略 + 仓储抽象，实现了 Human Approval、Interrupt、Resume、
  Pause、Continue、Reject、Retry、Modify Task、Multi-Level Approval、Human Feedback、
  Feedback Learning、Checkpoint 全套能力。
- **接入层**：REST API + 读模型投影 + 全局异常处理，把能力暴露成干净的 HTTP 端点。
- **实战层**：ERP 删单端到端跑通「规划 → 多级审批 → 执行」的三段式流水线。
- **运营层**：企业审批中心把全模块能力聚成一块「一眼看全局」的仪表盘。

整个模块严守六边形架构：依赖只向内，内核不知道实战和运营层的存在。这意味着你今天为
「删测试订单」写的这套东西，明天换个业务名词——退款、封号、扩缩容——就能整套复用。
**这就是把 HITL 做成独立模块的终极价值。**

### 5.4 生产化落地建议

1. **仓储换实现**：`InMemoryApprovalRepository` → Redis（快、TTL 支持超时）+ PostgreSQL
   （稳、可审计），接口 `ApprovalRepository` 一行不改。
2. **并发加锁**：approve/execute 按 `requestId` 加分布式锁（Redisson），防竞态。
3. **审计持久化**：决策审计链 `ApprovalDecision` 落库，满足合规「谁在何时批了什么」。
4. **超时驱动**：把内存超时扫描换成分布式定时任务 / 消息延迟队列。
5. **仪表盘预聚合**：数据量大时用事件驱动更新计数，避免每次全表扫描。
6. **可观测**：接入 Prometheus 暴露待办数/驳回率/超时率指标，配告警。

### 5.5 FAQ

**Q1：审批中心为什么用「遍历所有状态」而不给仓储加 findAll？**
A：全表扫描在生产是危险操作，不该在通用接口上暴露。遍历状态合并既复用了既有接口、不改内核，
又把「全表扫描」的语义显式化——生产实现里直接换成一条带过滤条件的 SQL 即可，调用方无感。

**Q2：仪表盘每次都全量重算，性能不会有问题吗？**
A：教学内存实现开销可忽略。生产数据量大时有两条路：加短 TTL 缓存（秒级），或事件驱动预聚合
（审批状态每变一次就增量更新计数）。按刷新频率和数据量权衡。

**Q3：为什么 approved 和 finalApproved 要分开统计？**
A：单级审批的终态是 APPROVED，多级会签的终态是 FINAL_APPROVED，中间态也叫 APPROVED。
分开统计才能准确回答「有多少彻底走完了流程」——运营和审计都需要这个精确区分。

**Q4：这个模块能直接集成进 ZeroHub AI Agent Platform 吗？**
A：能。整个 `day11humanintheloop` 是自包含模块，对外只依赖 Spring 与标准库。集成时把
`HumanLoopConfig` 纳入应用上下文、仓储换成生产实现即可，Agent 侧只需在高危动作前调
`approvalEngine.submit()` 并在执行前查状态。

**Q5：如果我只想要审批引擎，不要 ERP 实战和审批中心呢？**
A：直接删掉 `erpdemo` 和 `approvalcenter` 两个包，内核照样独立编译运行。这正是六边形架构
「依赖只向内」的好处——外层是可插拔的，内核自给自足。

### 5.6 面试题

1. **「什么是 CQRS？审批中心为什么是 CQRS 的读侧？」**
   考点：命令查询职责分离；写侧管状态流转、读侧管聚合投影；读侧零副作用，可缓存可并发。

2. **「为什么审批中心不给仓储加 findAll，而是遍历所有状态查询？」**
   考点：全表扫描是危险操作不应暴露成通用接口；遍历合并复用既有接口、不改内核、语义显式。

3. **「统计各状态计数时，为什么要先给每个状态预置 0？」**
   考点：保证维度完整；计数为 0 的状态也是有意义的数据；否则前端图表缺维度。

4. **「Day 11 整个 HITL 模块如何保证『人类闸门不可绕过』？请从架构层面回答。」**
   考点：状态机校验流转 + 唯一数据变更入口 + 入口处状态守卫 + 执行时回查最新状态；代码结构强制。

5. **「这套 HITL 模块如何做到可插拔、可复用？体现了什么架构思想？」**
   考点：六边形架构，依赖只向内；内核不依赖业务实战包；删掉外层内核照样跑；换业务名词即可复用。

---

> **本章交付物**：`approvalcenter` 包 3 个文件（ApprovalDashboard / ApprovalCenterService /
> ApprovalCenterController），提供只读审批仪表盘端点，javac 编译通过（day11 模块共 53 个源文件
> 零报错）。至此 Day 11「人机协同」十章全部完成——从审批引擎内核到 ERP 实战再到企业审批中心，
> 一套可直接集成进 ZeroHub 的企业级 HITL 模块交付完毕。