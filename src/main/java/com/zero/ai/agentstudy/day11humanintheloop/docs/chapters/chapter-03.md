# Chapter 03 · Approval Engine —— 把「零件」组装成「可用的审批服务」

> 本章目标：在 Chapter 02 打造的领域模型（`ApprovalRequest` / `ApprovalDecision` / `ApprovalStateMachine` / `RiskPolicy`）之上，构建**审批引擎（Approval Engine）**与**仓储抽象（Repository）**，把散落的零件编排成一个可以对外提供服务的「用例入口」，并跑通一条端到端的单级审批闭环。

---

## 一、为什么要学：从「一堆能跑的零件」到「一个能用的服务」

### 1.1 一个真实的翻车现场

先看一段"能跑但要命"的代码——很多团队第一版 HITL 就长这样，把风险判断、状态流转、存储、通知全糊在 Controller 里：

```java
// 反面教材：审批逻辑散落在 Controller，谁都能改，谁都不敢改
@PostMapping("/approve")
public String approve(@RequestBody ApproveReq req) {
    // 1. 从数据库捞请求（存储逻辑）
    ApprovalRequest r = jdbcTemplate.queryForObject(...);
    // 2. 手写状态判断（流转规则散落这里）
    if ("PENDING".equals(r.getStatus())) {
        r.setStatus("APPROVED");
    } else if ("APPROVED".equals(r.getStatus())) {
        // 会签逻辑？这里又抄一遍……
    }
    // 3. 直接拼 SQL 存回去（又一处存储逻辑）
    jdbcTemplate.update("UPDATE approval SET status=? ...");
    // 4. 顺手发个通知（副作用混在一起）
    mailSender.send(...);
    return "ok";
}
```

这段代码有 4 宗罪：

| 罪状 | 后果 |
|------|------|
| 流转规则散落在 `if-else` | 第 6 章加「多级会签」时要改所有 Controller，改一个漏三个 |
| 存储逻辑（SQL）写死在业务里 | 想从 MySQL 换 Redis+PG？整个方法重写 |
| 风险判断缺失 | 删 1 条和删 100 万条走一样的流程，没有分级 |
| 副作用（发通知）混入 | 单元测试没法测，一测就真发邮件 |

**核心痛点**：没有一个「编排者」把零件组织起来，也没有一层「存储抽象」把业务和数据库解耦。

### 1.2 审批引擎解决什么

审批引擎（Approval Engine）就是那个**编排者（Orchestrator）**。它的职责边界非常清晰：

- **接收**上层（Agent / Controller）传来的动作或审批指令；
- **委托**风险策略算风险、委托状态机做流转、委托仓储做存储；
- **自己不发明任何规则**——流转规则只有一个来源：`ApprovalStateMachine`。

一句话：**引擎负责「编排」，不负责「实现细节」**。这就是本章两个关键抽象——`ApprovalEngine`（入站端口）与 `ApprovalRepository`（出站端口）——存在的理由。

---

## 二、是什么：六边形架构下的「引擎 + 仓储」

### 2.1 六边形架构（Hexagonal / Ports & Adapters）速览

```
                    ┌─────────────────────────────┐
   HTTP Controller  │                             │
   Agent 调用    ──▶ │  ApprovalEngine (入站端口)   │
   （入站适配器）    │        ↓ 编排                │
                    │  RiskPolicy / StateMachine   │  ← 核心域（纯逻辑，无框架）
                    │        ↓ 依赖                │
                    │  ApprovalRepository (出站端口)│
                    │                             │
                    └───────────┬─────────────────┘
                                │
              ┌─────────────────┼──────────────────┐
              ▼                 ▼                  ▼
     InMemoryRepository   RedisRepository   PostgresRepository
        （出站适配器：可替换，核心域无感知）
```

- **入站端口（Inbound Port）**：`ApprovalEngine` 接口——外界通过它调用业务。
- **出站端口（Outbound Port）**：`ApprovalRepository` 接口——业务通过它调用外部资源（存储）。
- **核心域**：`RiskPolicy` / `ApprovalStateMachine` / 领域模型——纯 Java，不依赖任何框架，可独立单测。

这套结构的**最大好处**：核心域对「谁调它」「它存哪」一无所知，因此可以任意替换适配器（内存换 Redis+PG）而**核心域一行不改**。

### 2.2 本章交付的四个类

| 类 | 角色 | 位置 |
|----|------|------|
| `ApprovalRepository` | 出站端口（抽象） | `approvalengine/repository/` |
| `InMemoryApprovalRepository` | 出站适配器（内存实现） | `approvalengine/repository/` |
| `ApprovalEngine` | 入站端口（抽象） | `approvalengine/` |
| `DefaultApprovalEngine` | 引擎实现（编排者） | `approvalengine/` |

---

## 三、怎么用：逐类精讲

### 3.1 `ApprovalRepository`——把「存哪、怎么存」抽象掉

```java
public interface ApprovalRepository {
    void save(ApprovalRequest request);                       // 保存/更新（幂等覆盖）
    Optional<ApprovalRequest> findById(String requestId);     // 按 ID 查
    List<ApprovalRequest> findByStatus(ApprovalStatus status);// 按状态查（超时扫描用）
    List<ApprovalRequest> findByTaskId(String taskId);        // 按任务查
    void deleteById(String requestId);                        // 删除（审计场景通常不删）
    long count();                                             // 统计（测试/监控用）
}
```

**设计要点逐条讲：**

1. **`save` 是「保存或更新」，按 `requestId` 幂等覆盖。** 这样引擎里「加载→变更→保存」的写回逻辑不需要区分 insert 还是 update，简化调用方。
2. **`findByStatus` 是为第 6 章的「超时扫描」预留的。** 定时任务会 `findByStatus(PENDING)` 捞出所有待审批请求，逐个判断是否超时。接口先立在这里，是「面向未来编程」。
3. **`deleteById` 存在但慎用。** 审批记录是合规审计的一部分，真实项目里几乎只增不删，删除接口主要给测试清理用。
4. **返回 `Optional` 而非 `null`。** 强制调用方处理"不存在"的情况，从类型层面消灭 NPE。

### 3.2 `InMemoryApprovalRepository`——教学/单测用的内存实现

```java
public class InMemoryApprovalRepository implements ApprovalRepository {
    private final ConcurrentHashMap<String, ApprovalRequest> store = new ConcurrentHashMap<>();

    @Override public void save(ApprovalRequest request) {
        store.put(request.getRequestId(), request);
    }
    @Override public Optional<ApprovalRequest> findById(String requestId) {
        return Optional.ofNullable(store.get(requestId));
    }
    @Override public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
        return store.values().stream().filter(r -> r.getStatus() == status).collect(Collectors.toList());
    }
    // findByTaskId / deleteById / count 同理
}
```

**为什么用 `ConcurrentHashMap` 而不是 `HashMap`？**
审批场景天然并发（多个审批人、定时任务、Agent 同时读写），`HashMap` 在并发写下会死循环/数据丢失。`ConcurrentHashMap` 保证单个 `put/get` 的线程安全。

**⚠️ 但要划重点：`ConcurrentHashMap` 只保证单次操作原子，不保证「加载→变更→保存」这个复合操作的原子性。** 这正是引擎里为什么要预留分布式锁扩展位的原因（见 3.4）。

**内存实现的红线：重启即丢，绝不能上生产。** 审批记录必须持久化，否则服务重启后所有待审批请求凭空消失——这在金融/运维场景是灾难。

### 3.3 `ApprovalEngine`——审批业务的「用例入口」

```java
public interface ApprovalEngine {
    ApprovalRequest submit(AgentAction action);                                   // 提交动作，创建请求
    ApprovalStatus approve(String requestId, String approver, String comment);    // 批准（推进一级）
    ApprovalStatus reject(String requestId, String approver, String comment);     // 驳回（终态）
    ApprovalStatus modify(String requestId, String approver, String comment,
                          Map<String, Object> modifiedParams);                    // 修改参数
    ApprovalStatus resubmit(String requestId, String operator);                   // 重新提交
    ApprovalStatus abort(String requestId, String operator, String reason);       // 主动终止
    Optional<ApprovalRequest> query(String requestId);                            // 查询
}
```

接口用**动词命名**（submit / approve / reject / modify / resubmit / abort），对上层暴露的是「业务语言」而非「技术语言」。上层不需要知道背后有状态机、有风险策略、有仓储——它只管说「我要批准」。

### 3.4 `DefaultApprovalEngine`——编排者的实现

**（1）构造函数注入三个协作者**

```java
public class DefaultApprovalEngine implements ApprovalEngine {
    private final RiskPolicy riskPolicy;             // 算风险
    private final ApprovalStateMachine stateMachine; // 做流转
    private final ApprovalRepository repository;     // 做存储

    public DefaultApprovalEngine(RiskPolicy riskPolicy,
                                 ApprovalStateMachine stateMachine,
                                 ApprovalRepository repository) {
        this.riskPolicy = riskPolicy;
        this.stateMachine = stateMachine;
        this.repository = repository;
    }
}
```

三个 `final` 字段全部由构造器注入——这是**依赖倒置**的落地：引擎依赖的是三个**接口/抽象**，而非具体实现。测试时可以注入 mock，生产时注入 Redis 实现，引擎无感知。

**（2）`submit`——评估风险、决定级数、落库**

```java
@Override
public ApprovalRequest submit(AgentAction action) {
    RiskLevel level = riskPolicy.evaluate(action);           // ① 委托策略算风险
    int levels = decideLevels(level);                        // ② 风险→审批级数
    ApprovalRequest request = (levels <= 1)
            ? ApprovalRequest.single(action, level)          // ③ 单级
            : ApprovalRequest.multiLevel(action, level, levels); // 或多级
    repository.save(request);                                // ④ 落库 PENDING
    return request;
}

private int decideLevels(RiskLevel level) {
    return switch (level) {
        case NONE, LOW -> 1;   // 无/低风险：一级审批（也留痕）
        case HIGH -> 2;        // 高风险：两级会签
    };
}
```

注意引擎**没有自己写"什么算高风险"**——那是 `RiskPolicy` 的事；也**没有自己写状态**——`ApprovalRequest.single/multiLevel` 工厂方法负责初始化为 `PENDING`。引擎只做「编排」。

**（3）`approve` / `reject` / `modify`——统一的「加载→委托状态机→保存」三段式**

```java
@Override
public ApprovalStatus approve(String requestId, String approver, String comment) {
    ApprovalRequest request = require(requestId);                    // ① 加载（不存在抛异常）
    ApprovalDecision decision = ApprovalDecision.approve(approver, comment); // ② 构造决策
    ApprovalStatus status = stateMachine.fire(request, decision);   // ③ 委托状态机流转
    repository.save(request);                                       // ④ 保存
    return status;
}
```

三个写方法结构完全一致，差异只在第 ② 步构造的 `ApprovalDecision` 不同。**流转合法性、审计记录、多级会签判断，全部在 `stateMachine.fire()` 内部完成**，引擎不重复实现——这就是「规则单一来源」。

**（4）`require`——加载并校验存在**

```java
private ApprovalRequest require(String requestId) {
    return repository.findById(requestId)
            .orElseThrow(() -> new IllegalArgumentException("审批请求不存在：" + requestId));
}
```

把「查不到就抛异常」这个前置校验抽成私有方法，所有写方法复用，避免每处都写一遍 `if (opt.isEmpty()) throw`。

**（5）并发扩展位——真实项目的关键**

类的 Javadoc 里明确写了：

> approve/reject 等写操作在分布式环境必须加分布式锁（如 Redisson，按 requestId 加锁），锁的接入点就在每个写方法的开头。

为什么？因为「加载→变更→保存」不是原子的。设想两个审批人 A、B 同时批准同一个两级会签请求：

```
时刻 t1: A 加载 request（approvedLevels=0）
时刻 t2: B 加载 request（approvedLevels=0）  ← 读到的还是旧值！
时刻 t3: A fire → approvedLevels=1，保存
时刻 t4: B fire → approvedLevels=1，保存    ← 覆盖了 A，一次会签丢了！
```

结果：两个人都点了同意，系统却只记了一级。**解决方案**：写方法开头按 `requestId` 加分布式锁，让「加载→变更→保存」串行化。本章先留扩展位，第 6 章多级会签时正式接入。

### 3.5 端到端演示：跑通一条单级审批闭环

把四个类组装起来，跑一条完整的「提交→批准」闭环：

```java
public class ApprovalEngineDemo {
    public static void main(String[] args) {
        // 1) 组装引擎（生产环境由 Spring 注入，这里手动 new）
        RiskPolicy riskPolicy = new DefaultRiskPolicy();
        ApprovalStateMachine stateMachine = new ApprovalStateMachine();
        ApprovalRepository repository = new InMemoryApprovalRepository();
        ApprovalEngine engine = new DefaultApprovalEngine(riskPolicy, stateMachine, repository);

        // 2) Agent 想执行一个动作：删除 5 条测试订单（低风险 → 一级审批）
        AgentAction action = AgentAction.of(
                "task-erp-001", "DELETE_ORDER", "删除测试订单",
                Map.of("count", 5, "env", "test"));

        // 3) 提交，创建审批请求（状态 PENDING）
        ApprovalRequest request = engine.submit(action);
        System.out.println("提交后状态 = " + request.getStatus());        // PENDING
        System.out.println("风险等级   = " + request.getRiskLevel());      // NONE / LOW

        // 4) 运维主管批准
        ApprovalStatus status = engine.approve(
                request.getRequestId(), "ops-manager-zhang", "测试环境，同意删除");
        System.out.println("批准后状态 = " + status);                      // FINAL_APPROVED（单级直接终态）

        // 5) 查询审计链
        engine.query(request.getRequestId()).ifPresent(r ->
                r.getDecisions().forEach(d ->
                        System.out.println("审计: " + d.approver() + " → " + d.transition() + " @ " + d.decidedAt())));
    }
}
```

**预期输出：**

```
提交后状态 = PENDING
风险等级   = LOW
批准后状态 = FINAL_APPROVED
审计: ops-manager-zhang → APPROVE @ 2026-07-27T10:30:00
```

**闭环全景（时序）：**

```
Agent          Engine            RiskPolicy   StateMachine    Repository
  │  submit()     │                   │             │              │
  ├──────────────▶│  evaluate()       │             │              │
  │               ├──────────────────▶│             │              │
  │               │◀── LOW ───────────┤             │              │
  │               │  single(...)      │             │              │
  │               │  save() ──────────┼─────────────┼─────────────▶│  PENDING
  │◀── request ───┤                   │             │              │
  │  approve()    │                   │             │              │
  ├──────────────▶│  fire() ──────────┼────────────▶│              │
  │               │        校验+审计+改状态           │              │
  │               │◀── FINAL_APPROVED ┼─────────────┤              │
  │               │  save() ──────────┼─────────────┼─────────────▶│  FINAL_APPROVED
  │◀── status ────┤                   │             │              │
```

---

## 四、用在哪：真实项目里的引擎角色

### 场景一：ERP 批量删单（本课程最终实战）

Agent 规划出「删除 12 万条历史订单」，`RiskPolicy` 因数量巨大判为 `HIGH` → 引擎创建**两级会签**请求 → DBA 一级批准（仍 PENDING）→ 运维总监二级批准（FINAL_APPROVED）→ Agent 收到终态放行执行。引擎在这里是「放行闸机」，没有它 Agent 不敢也不能删。

### 场景二：金融交易审批

交易 Agent 发起「转账 500 万」，`RiskPolicy` 按金额阈值判 `HIGH`，引擎创建多级审批，风控专员 + 分行行长两级签字，全程 `decisions` 审计链留痕，满足银保监合规要求。引擎是「合规链的记录者」。

### 场景三：运维危险操作

SRE Agent 想「重启生产集群」，引擎创建审批 → 主管审批时用 `modify` 把「重启全部节点」改成「灰度重启 3 个节点」→ Agent `resubmit` → 二次确认后执行。引擎在这里承载了「人类修正 Agent 决策」的核心能力。

### 场景四：内容审核

内容生成 Agent 产出一批文案，引擎批量创建审批请求，审核员逐条 approve/reject，`findByStatus(PENDING)` 拉出待审队列驱动审核 UI。引擎是「人机协作队列」的中枢。

---

## 五、避坑指南 + 小结 + FAQ + 面试题

### 5.1 避坑 12 条

1. **别把内存实现带上生产。** `InMemoryApprovalRepository` 重启即丢，生产必须换 Redis+PG，审批记录必须持久化。
2. **别在引擎里写流转规则。** 一旦引擎里出现 `if (status == PENDING)`，说明规则泄漏了，流转规则的唯一来源永远是状态机。
3. **写操作必须加分布式锁。** 「加载→变更→保存」非原子，并发下会丢更新，按 `requestId` 加 Redisson 锁。
4. **`save` 要保证幂等。** 按 `requestId` 覆盖，避免重复提交产生多条记录。
5. **`findById` 返回 `Optional`，禁止返回 `null`。** 从类型层面消灭 NPE，调用方必须显式处理不存在。
6. **副作用（发通知/发 MQ）不要塞进引擎核心方法。** 会污染单测，应通过领域事件或独立的通知服务解耦。
7. **`require` 抛的异常要能被上层区分。** 「请求不存在」应转成 404 而非 500，别让 `IllegalArgumentException` 直接冒泡成系统错误。
8. **`decideLevels` 的规则应可配置。** 写死 `HIGH→2` 在真实项目不够灵活，应做成配置或策略，不同租户级数不同。
9. **别忽略 `submit` 返回的请求对象。** 上层需要拿 `requestId` 去查询/推进，丢了它就失联了。
10. **多级会签下 `approve` 可能仍返回 PENDING。** 上层不能假设 approve 后一定是终态，必须看返回的实际状态。
11. **删除接口慎用。** 审批记录是审计凭证，`deleteById` 主要给测试清理，生产几乎不删。
12. **引擎不要吞异常。** 状态机抛的 `IllegalTransitionException` 应向上传播，让调用方知道「这个操作在当前状态非法」，而不是静默返回原状态。

### 5.2 小结

- 引擎（`ApprovalEngine`）= **入站端口**，是审批业务的用例入口，用动词方法对上层暴露业务语言。
- 仓储（`ApprovalRepository`）= **出站端口**，把「存哪、怎么存」抽象掉，实现可从内存无缝换到 Redis+PG。
- `DefaultApprovalEngine` 是**编排者**：组合风险策略、状态机、仓储三个协作者，自己不发明规则。
- 所有写操作统一「加载→委托状态机→保存」三段式，保证「规则单一来源」。
- 并发安全靠分布式锁（本章留扩展位，第 6 章接入）。

### 5.3 FAQ

**Q1：为什么引擎不直接依赖 `InMemoryApprovalRepository`，非要搞个接口？**
A：为了可替换。生产要换 Redis+PG，测试要用内存，依赖接口才能不改引擎一行代码就替换实现——这是依赖倒置的价值。

**Q2：`submit` 里为什么区分 single 和 multiLevel，不能统一吗？**
A：单级和多级的初始 `expectedLevels` 不同，工厂方法封装了这个差异。引擎只需判断 `levels <= 1`，无需关心内部字段怎么初始化。

**Q3：单级审批为什么 approve 一次就到 FINAL_APPROVED？**
A：状态机 `fire()` 内部判断 `allLevelsApproved()`，单级请求批准一次就满足所有级数，自动 FINALIZE 到终态。这个逻辑在状态机里，引擎无感知。

**Q4：如果两个人同时 approve 会怎样？**
A：本章内存实现下会丢更新（见 3.4）。生产必须加分布式锁串行化，这是第 6 章的重点。

**Q5：引擎方法为什么返回 `ApprovalStatus` 而不是整个 `ApprovalRequest`？**
A：写方法调用方通常只关心「现在到哪个状态了」，返回状态更聚焦；需要完整对象时用 `query()`。

### 5.4 面试题

1. **什么是六边形架构的入站端口和出站端口？** 本章 `ApprovalEngine` 和 `ApprovalRepository` 分别是什么？
2. **为什么审批引擎不应该自己实现状态流转规则？** 「规则单一来源」有什么好处？
3. **`ConcurrentHashMap` 能保证「加载→变更→保存」的原子性吗？** 为什么？如何解决？
4. **依赖倒置原则（DIP）在 `DefaultApprovalEngine` 里是如何体现的？**
5. **为什么仓储的 `findById` 返回 `Optional` 而不是 `null`？** 有什么工程价值？
6. **单级审批和多级会签在引擎的 `submit` / `approve` 里有何不同表现？**
7. **审批系统里为什么审批记录几乎只增不删？** 这对 `deleteById` 接口的设计有什么启示？
8. **如果要把内存仓储换成 Redis+PG 的组合实现，引擎需要改动吗？为什么？**

---

> 本章完。下一章（Chapter 04）我们将进入 **Interrupt / Resume——Agent 执行的挂起与恢复**：当 Agent 执行到危险动作时如何「暂停」等待人类审批，审批通过后又如何「恢复」到中断点继续执行。