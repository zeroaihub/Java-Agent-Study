# Chapter 06 多级审批与超时：会签链与到点兜底

> 本章隶属 Day11「Human-in-the-loop 人机协同 Agent」模块。前五章我们造好了单级审批引擎、中断/恢复、检查点。本章把审批从「一个人拍板」升级为「一条链逐级会签」，并解决那个所有审批系统都逃不掉的问题——**没人批怎么办**。

---

## 一、为什么要学：单级审批扛不住真实企业

### 1.1 一个真实的翻车现场

某电商公司的运营 Agent，接到「清理测试订单」任务，准备批量删除 12,000 条订单。系统按 Chapter 03 的单级审批设计：值班运营小王点了「同意」，Agent 立刻开删。

事后复盘发现：这 12,000 条里混进了 340 条**真实客户订单**——测试数据和生产数据的标记字段被上游脏数据污染了。小王是运营，根本没有能力判断「这批订单能不能删」，他只是看到系统弹窗、习惯性点了同意。

**问题出在哪？** 不是小王不负责，而是**这个动作的风险等级，根本不该由一个运营单独拍板**。删 12,000 条订单，至少应该：

1. 运营主管先看「删除范围合不合理」；
2. 数据负责人再看「有没有误伤生产数据」；
3. 高危量级下，风控总监终审。

这就是**多级审批（Multi-Level Approval）**，也叫**会签**——一件事必须经过多个层级、多个角色依次点头，才算真正通过。任何一级说不，整件事就否决。

### 1.2 另一个更隐蔽的坑：没人批

多级审批还带来一个新问题：链条越长，越容易卡住。

想象三级审批链，一级批了、二级批了，三级的风控总监出差了，请求就这么挂在 PENDING 状态。Agent 在傻等，业务在停摆，而这个动作可能是「紧急下线一个出问题的营销活动」——**等得越久，损失越大**。

反过来，如果是低风险动作（比如「清理 7 天前的临时缓存文件」），挂着没人批也不该无限期阻塞。

所以多级审批必须配一套**超时机制（Timeout Policy）**：到点没人批，系统按预设规则自动兜底——该拒的拒、该升级的升级、该放行的放行。

### 1.3 本章要解决的三个核心问题

| 问题 | 单级审批的答案 | 本章的答案 |
| --- | --- | --- |
| 谁来批？ | 一个人 | 一条链，逐级、按角色 |
| 批几次算过？ | 一次 | N 次，每级都要过 |
| 没人批怎么办？ | 没考虑 | 超时策略：拒绝 / 升级 / 自动通过 |

学完本章，你将拥有一套**可配置层级、可校验权限、可超时兜底**的企业级会签引擎，且**不重写任何领域模型**——因为 Chapter 02 的状态机早就为多级会签埋好了伏笔。

---

## 二、是什么：会签链 + 超时策略的完整拼图

### 2.1 先复习：Chapter 02 埋好的多级基础设施

打开 [`ApprovalRequest`](../../humancore/model/ApprovalRequest.java) 和 [`ApprovalStateMachine`](../../humancore/statemachine/ApprovalStateMachine.java)，你会发现多级会签的「骨架」早就在了：

- `ApprovalRequest.requiredLevels`：这次审批需要几级（1 = 单级，N = N 级会签）。
- `ApprovalRequest.approvedLevels`：已经通过了几级。
- `ApprovalRequest.allLevelsApproved()`：`approvedLevels >= requiredLevels`，判断是否全通过。
- `ApprovalStateMachine.fire()`：一次 APPROVE 之后**自动判断**——还有下一级就走 `NEXT_LEVEL` 回到 PENDING，全过了就走 `FINALIZE` 到 FINAL_APPROVED。

也就是说，**状态机已经会「逐级流转」了**。本章缺的不是流转逻辑，而是三块「配置层」和「兜底层」的拼图：

1. **每一级由谁批**（权限）——`ApprovalLevel` / `ApprovalChain`；
2. **提交时按链创建、审批时校验权限**（编排）——`MultiLevelApprovalService`；
3. **没人批时怎么办**（兜底）——`TimeoutPolicy` / `ApprovalTimeoutHandler`。

### 2.2 本章新增的五个类

| 类 | 角色 | 一句话职责 |
| --- | --- | --- |
| [`ApprovalLevel`](../../multilevelapproval/ApprovalLevel.java) | 值对象 | 第 N 级由哪个角色、哪些人批，多久超时 |
| [`ApprovalChain`](../../multilevelapproval/ApprovalChain.java) | 值对象 | 把若干级串成完整会签链，定位「下一个该谁批」 |
| [`TimeoutPolicy`](../../multilevelapproval/TimeoutPolicy.java) | 枚举 | 超时策略：拒绝 / 升级 / 自动通过 |
| [`MultiLevelApprovalService`](../../multilevelapproval/MultiLevelApprovalService.java) | 编排器 | 按链提交、逐级审批（含权限校验）、驱动状态机 |
| [`ApprovalTimeoutHandler`](../../multilevelapproval/ApprovalTimeoutHandler.java) | 兜底哨兵 | 扫描超时请求，按策略施加终局 |

### 2.3 组件协作全景图

```
                         ┌─────────────────────────────┐
        提交动作          │   MultiLevelApprovalService  │
   ──────────────────▶   │        （会签编排器）         │
                         └──────────────┬──────────────┘
                                        │ 用链的级数
                                        ▼
                    ┌───────────────────────────────────┐
                    │  ApprovalChain（一条链）           │
                    │  ┌────────┐ ┌────────┐ ┌────────┐  │
                    │  │Level 1 │ │Level 2 │ │Level 3 │  │
                    │  │主管    │ │数据负责│ │风控总监│  │
                    │  └────────┘ └────────┘ └────────┘  │
                    └───────────────────────────────────┘
                                        │
       每次 approve 前先问链：           │ canApproveNow(approver, approvedLevels)
       「这个人能批当前级吗？」          ▼
                    ┌───────────────────────────────────┐
                  │  ApprovalStateMachine.fire()       │
                    │  APPROVE → 自动判断：              │
                    │    还有下一级？ → NEXT_LEVEL(PENDING)│
                    │    全过了？     → FINALIZE(FINAL)   │
                    └───────────────────────────────────┘

       没人批时，另一条线：
                    ┌───────────────────────────────────┐
       定时扫描 ───▶│  ApprovalTimeoutHandler.sweep()    │
                   │  找出 isExpired() 的 PENDING 请求   │
                    │  按 TimeoutPolicy 施加终局：        │
                    │    REJECT / ESCALATE → TIMEOUT 终态 │
                    │    AUTO_APPROVE → 系统补批一路放行   │
                    └───────────────────────────────────┘
```

---

## 三、怎么用：逐类精讲

### 3.1 ApprovalLevel：一级的规则

[`ApprovalLevel`](../../multilevelapproval/ApprovalLevel.java) 是不可变 record，描述「第 N 级由谁批、多久超时」：

```java
public record ApprovalLevel(int level,
                            String roleName,
                            List<String> approvers,
                            long timeoutSeconds) {
    public ApprovalLevel {
        if (level < 1) throw new IllegalArgumentException(...);
        Objects.requireNonNull(roleName, ...);
        if (approvers.isEmpty()) throw new IllegalArgumentException(...);
        if (timeoutSeconds <= 0) throw new IllegalArgumentException(...);
        approvers = List.copyOf(approvers);   // 防御性拷贝
    }
    public boolean canApprove(String approver) {
        return approvers.contains(approver);
    }
}
```

**设计要点**：

- **为什么用 record 而非普通类？** 配置一旦下发就不该被运行时篡改，不可变保证「同一请求的评审规则确定」——防止有人中途偷改审批人白名单。
- **为什么 approvers 是 List 而非单个 String？** 一级往往是「一组人任一批准即可」，比如「三个值班主管，谁在线谁批」。这就是审批组的概念。
- **为什么每级独立配 timeoutSeconds？** 不同级的紧迫度不同——一级快审（1 小时催一催），三级慢审（24 小时留足决策时间）。

### 3.2 ApprovalChain：整条会签链

[`ApprovalChain`](../../multilevelapproval/ApprovalChain.java) 把若干级串起来，核心是两个能力：**定位下一级** 和 **校验权限**。

```java
public ApprovalLevel levelFor(int approvedLevels) {
    // approvedLevels=0 → 下一个该批第 1 级（下标 0）
    // 下标 = approvedLevels，天然对齐
    return levels.get(approvedLevels);
}

public boolean canApproveNow(String approver, int approvedLevels) {
    if (approvedLevels >= levels.size()) return false;
    return levels.get(approvedLevels).canApprove(approver);
}
```

**最关键的设计：下标 = approvedLevels**。已通过 0 级 → 该批第 1 级 → levels 下标 0；已通过 1 级 → 该批第 2 级 → 下标 1。这个「已通过级数天然等于下一级下标」的对齐，让权限定位变成一次 O(1) 的数组访问，无需额外维护「当前级指针」。

构造时还做了**层级完整性校验**（级号必须 1,2,3...N 连续），杜绝「配了 1 级和 3 级、漏了 2 级」这种运行期才爆炸的坑。

### 3.3 MultiLevelApprovalService：会签编排器

[`MultiLevelApprovalService`](../../multilevelapproval/MultiLevelApprovalService.java) 是本章的用例入口，和 Chapter 03 的 `DefaultApprovalEngine` 平级——一个管单级，一个管多级，但都**不发明流转规则，一律委托状态机**。

核心是 `approve` 的三步：

```java
public ApprovalStatus approve(String requestId, String approver, String comment) {
    ApprovalRequest request = require(requestId);
    ApprovalChain chain = requireChain(requestId);

    // ① 权限校验：这个人能批当前级吗？
    if (!chain.canApproveNow(approver, request.getApprovedLevels())) {
        throw new IllegalStateException("审批人无权审批第 " + (request.getApprovedLevels()+1) + " 级");
    }

    // ② 委托状态机（内部自动判断 NEXT_LEVEL / FINALIZE）
    ApprovalDecision decision = ApprovalDecision.approve(approver, comment);
    ApprovalStatus status = stateMachine.fire(request, decision);

    // ③ 落库
    repository.save(request);
    return status;
}
```

**为什么权限校验放在编排器、而非状态机？** 职责分离：状态机只管「状态能不能这么流转」（业务无关的通用规则），**「谁有资格触发流转」是业务权限，属于编排层**。这样状态机保持纯粹、可复用，权限逻辑集中在一处、易审计。

### 3.4 TimeoutPolicy：三种超时策略

[`TimeoutPolicy`](../../multilevelapproval/TimeoutPolicy.java) 枚举三种兜底方式：

| 策略 | 语义 | 适用场景 | 风险 |
| --- | --- | --- | --- |
| `REJECT` | 超时即拒 | 高危动作（删库、大额转账） | 最安全，宁可错杀 |
| `ESCALATE` | 超时升级到兜底人 | 必须有人拍板的关键流程 | 中等，防流程卡死 |
| `AUTO_APPROVE` | 超时自动通过 | 低风险、追求吞吐 | **高危严禁**，否则成绕过后门 |

**为什么用枚举而非 boolean/if-else？** 开闭原则——新增策略只是加一个枚举值加一段分支，不改动已有代码；而且不同风险等级 / 审批链可以挂不同策略，天然可扩展。

### 3.5 ApprovalTimeoutHandler：到点兜底的哨兵

[`ApprovalTimeoutHandler`](../../multilevelapproval/ApprovalTimeoutHandler.java) 是「定时巡逻的哨兵」，`sweep` 扫描超时请求，`handleIfExpired` 处理单个：

```java
public boolean handleIfExpired(ApprovalRequest request, TimeoutPolicy policy) {
    if (request.getStatus().isTerminal()) return false;  // 已终态，跳过
    if (!request.isExpired()) return false;               // 没到期，跳过
    applyPolicy(request, policy);
    repository.save(request);
    return true;
}
```

**为什么超时也要走状态机？** 因为「超时 → TIMEOUT」本身就是一条**合法流转边**（Chapter 02 状态机表里 `PENDING --timeout--> TIMEOUT` 已定义）。让它和 approve/reject 走同一套 `fire`，才能保证审计链完整、状态一致，而不是绕过状态机偷偷改状态。

**为什么本类不含定时器？** 把「什么时候扫」（调度，交给 Spring `@Scheduled`/Quartz）和「扫到了怎么办」（策略）解耦。哨兵只负责「怎么办」，让调度层灵活替换（cron、固定频率、事件触发都行）。

---

## 四、用在哪：真实项目中的三类会签场景

### 4.1 三类典型场景对照

| 场景 | 审批链设计 | 超时策略 | 理由 |
| --- | --- | --- | --- |
| 金额分级审批（转账/退款） | 按金额分级：<1万一级、1-10万两级、>10万三级 | REJECT | 钱的事，超时宁可拦住 |
| 数据危险操作（批量删除） | 主管→数据负责人→风控 | REJECT | 误删不可逆，最保守 |
| 内部低敏操作（清缓存） | 值班一级 | AUTO_APPROVE | 低风险，追求吞吐不阻塞 |

**关键洞察：审批链的级数应由风险等级动态决定**，而不是写死。Chapter 03 的 `DefaultApprovalEngine.decideLevels(RiskLevel)` 已经示范了「风险→级数」的映射；多级场景下，可以进一步做成「风险→整条链」的映射工厂。

### 4.2 端到端 Demo：三级会签 + 权限校验

以「删除 12,000 条订单」为例，走一遍完整会签：

```java
// 1) 准备协作者
ApprovalStateMachine sm = new ApprovalStateMachine();
ApprovalRepository repo = new InMemoryApprovalRepository();
MultiLevelApprovalService service = new MultiLevelApprovalService(sm, repo);

// 2) 定义一条三级审批链
ApprovalChain chain = ApprovalChain.of("delete-orders-chain",
        ApprovalLevel.of(1, "运营主管", "manager_wang", 3600),
        ApprovalLevel.of(2, "数据负责人", "data_li", 3600),
        ApprovalLevel.of(3, "风控总监", "risk_zhao", 86400));

// 3) 提交动作（假设风险评估为 HIGH）
AgentAction action = AgentAction.of("task-001", "DELETE_ORDERS", "批量删除测试订单");
ApprovalRequest req = service.submit(action, RiskLevel.HIGH, chain);
String id = req.getRequestId();
// 此刻：status=PENDING, approvedLevels=0, 当前该批第 1 级

// 4) 一级：主管批准
service.approve(id, "manager_wang", "删除范围核对无误");
// status=PENDING（自动 NEXT_LEVEL 回到等待）, approvedLevels=1, 当前该批第 2 级

// 5) 越权尝试：三级的赵总想跳过二级直接批 → 抛异常
try {
    service.approve(id, "risk_zhao", "我先批了");
} catch (IllegalStateException e) {
    // "审批人 [risk_zhao] 无权审批第 2 级" —— 权限校验拦住越级
}

// 6) 二级：数据负责人批准
service.approve(id, "data_li", "已排查，无生产数据");
// status=PENDING, approvedLevels=2, 当前该批第 3 级

// 7) 三级：风控总监终审
ApprovalStatus finalStatus = service.approve(id, "risk_zhao", "同意执行");
// status=FINAL_APPROVED！allLevelsApproved()=true → 状态机自动 FINALIZE

assert finalStatus == ApprovalStatus.FINAL_APPROVED;
// 此时 Agent 才真正拿到「放行令牌」，开始执行删除
```

**执行时序**：

```
提交 ──▶ PENDING(0级)
           │ manager_wang APPROVE
           ▼
        [状态机] APPROVE → approvedLevels=1 → 未全过 → NEXT_LEVEL
           ▼
        PENDING(1级)  ← risk_zhao 越级？拒！
           │ data_li APPROVE
           ▼
        [状态机] approvedLevels=2 → 未全过 → NEXT_LEVEL
           ▼
        PENDING(2级)
           │ risk_zhao APPROVE
           ▼
        [状态机] approvedLevels=3 → 全过！ → FINALIZE
           ▼
        FINAL_APPROVED ✅ 放行
```

### 4.3 超时兜底 Demo

```java
ApprovalTimeoutHandler timeoutHandler = new ApprovalTimeoutHandler(sm, repo);

// 假设某高危请求挂了超过 expireAt……
// 由 Spring @Scheduled(fixedDelay=60000) 每分钟调一次：
int handled = timeoutHandler.sweep(TimeoutPolicy.REJECT);
// 所有超时的 PENDING/MODIFIED 请求 → TIMEOUT 终态，Agent 收到「不予放行」

// 低风险场景则挂 AUTO_APPROVE：
int autoApproved = timeoutHandler.sweep(TimeoutPolicy.AUTO_APPROVE);
// 超时请求被系统身份一路补批到 FINAL_APPROVED
```

**生产接法**（Spring）：

```java
@Component
class ApprovalTimeoutJob {
    private final ApprovalTimeoutHandler handler;
    // 每分钟扫一次，高危链用 REJECT
    @Scheduled(fixedDelay = 60_000)
    void scan() {
        int n = handler.sweep(TimeoutPolicy.REJECT);
        if (n > 0) log.warn("本轮超时处理 {} 个审批请求", n);
    }
}
```

---

## 五、避坑指南（13 条）+ 小结 + FAQ + 面试题

### 5.1 避坑指南

1. **别把权限校验塞进状态机**。状态机管「状态能不能流转」，权限管「谁能触发」，两者职责必须分离，否则状态机被业务污染、无法复用。
2. **审批链级号必须连续**。`ApprovalChain` 构造时强校验 1..N，别指望运行时才发现漏了一级——那时请求已经卡死。
3. **AUTO_APPROVE 绝不用于高危动作**。超时自动通过等于「没人管就放行」，对删库/转账是灾难性后门。高危一律 REJECT。
4. **超时也要走状态机 fire**，别直接 `applyStatus(TIMEOUT)` 绕过——会丢审计链、破坏一致性。
5. **boundChains 生产环境要持久化**。教学用内存 Map 存「请求→链」的绑定，重启即丢；生产必须随 ApprovalRequest 一起落库（或存链 ID 引用）。
6. **多级会签下 approvedLevels 只增不减**。它是 `recordDecision` 里 `isApproval()` 才 ++ 的，驳回不减——因为驳回直接进 REJECTED 终态，整链作废，不存在「退一级」。
7. **同一级不能同一人批两次凑数**。本教学模型未做「审批人去重」，生产要防「一个人在一级里反复点满足 requiredLevels」——虽然本设计一级只需一次 APPROVE，但审批组场景要警惕。
8. **expireAt 是整个请求的，不是每级的**。当前 `ApprovalRequest.expireAt` 是提交时算的全局超时；若要「每级独立超时」，需扩展模型记录「当前级的起始时间」，用 `ApprovalLevel.timeoutSeconds` 重算。这是本章模型的已知简化。
9. **sweep 只扫 PENDING/MODIFIED**。终态请求不该被扫（`isTerminal` 已挡），但别漏了 MODIFIED——它也会挂着等 resubmit。
10. **超时扫描要幂等**。哨兵可能被并发触发（多实例部署），`handleIfExpired` 里的 `isTerminal` 检查是幂等护栏：已处理过的不会重复处理。
11. **ESCALATE 本教学模型只记语义、不自动重开链**。真实升级要「重新发起一条兜底审批链给更高层」，这属于业务编排，别指望 handler 自动完成。
12. **写操作要加分布式锁**。多实例下两个审批人同时 approve 会竞态，生产环境必须按 requestId 加锁（Redisson），锁点就在 `approve` 开头。
13. **审批链配置应外置**。别把 `ApprovalChain.of(...)` 硬编码在代码里，生产应从配置中心/DB 加载，支持不改代码调整审批流。

### 5.2 小结

本章我们在**不重写任何领域模型**的前提下，用五个新类把审批升级成企业级会签：

- **配置层**：`ApprovalLevel`（一级规则）+ `ApprovalChain`（整条链，下标=approvedLevels 的巧妙对齐）；
- **编排层**：`MultiLevelApprovalService`（按链提交、逐级审批、权限校验），把「谁能批」的业务权限与状态机的「能不能流转」彻底分离；
- **兜底层**：`TimeoutPolicy`（三策略）+ `ApprovalTimeoutHandler`（哨兵，超时也走状态机保审计）。

核心心法：**Chapter 02 的状态机早就会逐级流转了，本章只是给它配上「谁批」和「没人批怎么办」**。这正是「领域模型稳定、编排层灵活」的分层威力——好的抽象让后续扩展变成「加配置」而非「改核心」。

### 5.3 FAQ

**Q1：为什么 approvedLevels 能直接当 levels 的下标？**
A：约定 approvedLevels=0 表示还没人批，下一个该批的是第 1 级（下标 0）。已通过 k 级 → 下一个批第 k+1 级 → 下标 k。天然对齐，无需额外维护指针。

**Q2：多级会签中间某级驳回会怎样？**
A：直接进 REJECTED 终态，整条链作废。不存在「退回上一级重批」——那需要更复杂的驳回路由，本模型采用「一票否决」的简洁语义。

**Q3：状态机怎么知道该 NEXT_LEVEL 还是 FINALIZE？**
A：`fire()` 里 APPROVE 后先 `applyStatus(APPROVED)`，再调 `allLevelsApproved()`：全过了走 FINALIZE→FINAL_APPROVED，没过完走 NEXT_LEVEL→PENDING。判断依据是 `approvedLevels >= requiredLevels`。

**Q4：超时策略能不能每级不同？**
A：`ApprovalLevel` 已经带了 `timeoutSeconds`（每级可不同），但 `TimeoutPolicy`（拒/升/放）本教学模型是整请求一个策略。要做「每级不同策略」，可把策略字段也下沉到 ApprovalLevel。

**Q5：为什么 handler 不自己起定时器？**
A：单一职责 + 可测性。定时是「调度关注点」，处理是「业务关注点」。handler 只暴露纯方法，测试时直接调、生产时 `@Scheduled` 调，互不耦合。

### 5.4 面试题（8 道）

1. 多级会签和单级审批在状态机层面有什么区别？（答：多了 NEXT_LEVEL/FINALIZE 两条边，APPROVE 后自动判断走哪条）
2. 为什么权限校验放在编排器而非状态机？（职责分离：状态流转 vs 触发资格）
3. `ApprovalChain` 为什么要在构造时校验级号连续？（提前暴露配置错误，避免运行期卡死）
4. 三种超时策略分别适合什么场景？为什么高危禁用 AUTO_APPROVE？
5. 超时处理为什么也要走状态机 fire 而非直接改状态？（审计链完整 + 状态一致）
6. approvedLevels 为什么能直接做 levels 下标？这个设计有什么好处？
7. 多实例部署下超时扫描如何保证幂等？（isTerminal 护栏 + 分布式锁）
8. 如果要支持「每级独立超时」，当前模型要怎么改？（记录当前级起始时间，用 ApprovalLevel.timeoutSeconds 重算 expireAt）

---

> **下一章预告**：Chapter 07 Feedback Engine——审批之外，人还能给 Agent「反馈」，让它从人类的纠正中学习（Human Feedback + Feedback Learning）。