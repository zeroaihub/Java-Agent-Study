# Chapter 05 ·Checkpoint Manager —— 让执行现场「跨重启存活」

> 本章目标：把 Chapter 04 只活在内存里的 `ExecutionContext`，通过「快照 + 持久化」变成可跨进程、跨重启恢复的 `Checkpoint`。我们将实现检查点值对象、存储端口、内存适配器与检查点管理器，为长时间挂起的 HITL 执行提供可靠性保障。

---

## 一、为什么要学：内存里的执行现场经不起一次重启

### 1.1 一个真实的翻车现场

Chapter 04 我们打通了「挂起-恢复」。设想一个 ERP 删单执行，跑到危险步被中断，正在 `WAITING_APPROVAL` 等主管审批。主管出差了，两天后才登录审批——

```java
// 周一 10:00：执行挂起，等待审批
ExecutionContext ctx = interruptManager.register("exec-001", "task-delete");
ctx.advanceStep(); ctx.advanceStep();
interruptManager.interrupt(InterruptSignal.forApproval("exec-001", 2, action, "等审批"));
// 状态 WAITING_APPROVAL，安静地待在 ConcurrentHashMap 里……

// 周一 22:00：服务发布，进程重启
// 💥 ConcurrentHashMap 清空，exec-001 的执行现场彻底消失

// 周三 09:00：主管终于来审批
resumeEngine.resume("exec-001");
// 💥 IllegalArgumentException: 执行现场不存在：exec-001
```

问题的根源：**执行现场只活在内存里**。任何一次发布、崩溃、OOM 重启，都会让所有挂起中的执行灰飞烟灭。而 HITL 的本质决定了「等人决策」可能长达数小时甚至数天——这远超一个进程的生命周期。

### 1.2 我们需要什么能力

- **快照（Snapshot）**：在关键时刻把执行现场冻结成不可变副本；
- **持久化（Persistence）**：把快照存到进程之外（Redis / 数据库），重启不丢；
- **还原（Restore）**：进程重启后，从最新快照重建执行现场，接着跑；
- **版本与回滚（Versioning）**：保留多个历史快照，支持「撤销到某个安全点」。

这就是 Checkpoint（检查点）机制——它是一切「长时间挂起可靠恢复」的地基。

---

## 二、是什么：Checkpoint 是执行现场的「不可变存档」

### 2.1 核心洞察：运行时对象 vs 持久化存档

| 维度 | `ExecutionContext`（运行时） | `Checkpoint`（存档） |
|------|------------------------------|----------------------|
| 可变性 | 可变（volatile 字段随执行变化） | **不可变**（record） |
| 生命周期 | 进程内，随重启消失 | 跨进程，持久保存 |
| 用途 | 高频读写、驱动执行 | 冻结、传输、恢复、审计 |
| 类比 | 正在编辑的文档 | 定时自动保存的存档文件 |

Checkpoint 就像游戏的「存档点」：你可以随时读档回到那一刻。它保存「恢复所需的最小充分状态」——状态、步号、变量快照 + 版本号,不多不少。

### 2.2 检查点机制的四个组件

```
   ┌──────────────────┐   capture()   ┌────────────┐   save()   ┌──────────────────┐
   │ ExecutionContext │ ────────────▶ │ Checkpoint │ ─────────▶ │ CheckpointStore  │
   │  （运行时·可变）   │  （冻结快照）  │ （不可变）  │  （持久化） │  （内存/Redis/PG） │
   └──────────────────┘               └────────────┘            └────────┬─────────┘
            ▲                                                            │ findLatest()
            │           rebuild()                                        │
            └────────────────────────────────────────────────────────────┘
                          从最新检查点重建执行现场（跨重启恢复）
```

### 2.3 本章交付的类

| 类 | 角色 | 位置 |
|----|------|------|
| `Checkpoint` | 检查点（不可变值对象 record） | `checkpointmanager/` |
| `CheckpointStore` | 存储端口（Outbound Port 接口） | `checkpointmanager/` |
| `InMemoryCheckpointStore` | 内存存储适配器 | `checkpointmanager/` |
| `CheckpointManager` | 检查点管理器（编排者） | `checkpointmanager/` |

---

## 三、怎么用：逐类精讲

### 3.1 `Checkpoint`——不可变存档

```java
public record Checkpoint(
        String checkpointId, String executionId, String taskId,
        long version, ExecutionState state,
        int currentStep, int resumeStep,
        Map<String, Object> variables, Instant createdAt) {

    public Checkpoint {
        // 非空校验 + 变量做防御性只读拷贝
        variables = (variables == null) ? Map.of() : Map.copyOf(variables);
    }

    public static Checkpoint capture(ExecutionContext ctx, long version) { ... }
}
```

**为什么用 `record`？** 检查点是「过去某一刻的事实」，一旦产生绝不允许被改。若可变，「读档恢复」就失去确定性——你以为恢复到 A，实际被人偷偷改成了 B。不可变性是检查点可信的根基。

**`capture()` 静态工厂**：把运行时 `ExecutionContext` 冻结成检查点。注意它调用 `ctx.snapshotVariables()`（Chapter 04 埋下的伏笔），拿到的是 `Map.copyOf` 的只读副本——冻结的是「值」而不是「引用」，之后执行现场再怎么变，这个检查点纹丝不动。

**`version` 字段**：同一执行会打多个检查点（step 1、step 2、中断前……），版本号单调递增，用于「取最新」和「回滚到指定版本」。

### 3.2 `CheckpointStore`——存储端口

```java
public interface CheckpointStore {
    void save(Checkpoint checkpoint);
    Optional<Checkpoint> findById(String checkpointId);
    Optional<Checkpoint> findLatest(String executionId);   // 恢复最常用
    List<Checkpoint> history(String executionId);           // 回滚/审计
    void deleteAll(String executionId);
}
```

这是**出站端口**：定义「检查点往哪存、怎么取」的契约,但不绑定任何存储介质。`CheckpointManager` 依赖这个接口而非具体实现——依赖倒置原则（DIP）。教学用内存实现跑通,生产换 Redis/PG,业务代码零改动。

`findLatest` 是恢复路径的核心：重启后我们只关心「这个执行最后存到哪了」。`history` 服务于审计和回滚。

### 3.3 `InMemoryCheckpointStore`——内存适配器

用两个索引：`byId`（精确查找）和 `byExecution`（按执行分桶存历史）。`findLatest` 用 `stream().max(comparingLong(version))` 取版本号最大者。

**这是「适配器」**：它适配「内存」这个具体机制到 `CheckpointStore` 抽象契约。生产环境写一个 `RedisCheckpointStore` 或 `JpaCheckpointStore` 实现同一接口即可替换。

### 3.4 `CheckpointManager`——编排者

**（1）打检查点**

```java
public Checkpoint checkpoint(ExecutionContext ctx) {
    long version = versionSeq
            .computeIfAbsent(ctx.getExecutionId(), k -> new AtomicLong(0))
            .incrementAndGet();               // 版本号原子自增
    Checkpoint cp = Checkpoint.capture(ctx, version);
    store.save(cp);
    return cp;
}
```

用 `AtomicLong` 保证并发打点时版本号不冲突。建议在这些时机打点：**中断前、恢复前、每完成一个关键步骤后**。

**（2）从最新检查点还原——跨重启恢复的入口**

```java
public Optional<ExecutionContext> restoreLatest(String executionId) {
    return store.findLatest(executionId).map(this::rebuild);
}
```

`rebuild()` 调用 Chapter 04 我们为 `ExecutionContext` 新增的「还原构造器」，把不可变检查点重新变成可变的运行时现场。进程重启后，只要 `restoreLatest` 拿回执行现场，`ResumeEngine.resume()` 就能接着跑——闭环完整了。

**（3）回滚到指定版本**

```java
public Optional<ExecutionContext> rollbackTo(String executionId, long version) {
    return store.history(executionId).stream()
            .filter(cp -> cp.version() == version)
            .findFirst().map(this::rebuild);
}
```

「撤销到某个安全点」的能力：执行跑歪了，可以读回更早的检查点重来。这是检查点相比「只存最新状态」的额外价值——它保留了历史。

### 3.5 与 Chapter 04 的衔接

Chapter 04 的 `ExecutionContext` 埋了两个伏笔，本章正好接上：
- `snapshotVariables()` 返回 `Map.copyOf` 只读副本 → `Checkpoint.capture()` 用它冻结变量；
- 本章为 `ExecutionContext` 新增了「还原构造器」→ `CheckpointManager.rebuild()` 用它重建现场。

运行时用内存对象（快），持久化用检查点（可靠），二者通过 capture / rebuild 双向转换——这就是完整的可靠性方案。

---

## 四、用在哪：真实项目中的 Checkpoint

### 4.1 三类典型场景

| 场景 | 打点时机 | 恢复方式 |
|------|----------|----------|
| ERP 删单挂起等审批（可能数天） | 中断前 checkpoint | 重启后 restoreLatest → resume |
| 长流程工作流（多步、每步耗时长） | 每完成一步 checkpoint | 崩溃后从最新检查点续跑，不重跑已完成步骤 |
| Agent 执行跑偏需回退 | 每个关键节点 checkpoint | rollbackTo 指定版本，从安全点重来 |

### 4.2 端到端演示：跨「进程重启」的恢复

```java
public class CheckpointDemo {
    public static void main(String[] args) {
        // === 组装：存储 + 检查点管理器 + 中断/恢复引擎 ===
        CheckpointStore store = new InMemoryCheckpointStore();
        CheckpointManager checkpointManager = new CheckpointManager(store);
        InterruptManager interruptManager = new InterruptManager();
        ResumeEngine resumeEngine = new ResumeEngine(interruptManager);

        String execId = "exec-20260727-777";

        // ================== 「进程 A」：执行 → 挂起 → 打检查点 ==================
        ExecutionContext ctx = interruptManager.register(execId, "task-erp-delete");
        ctx.putVariable("orderCount", 5);
        ctx.advanceStep();  // step 0 → 1: 查询
        ctx.advanceStep();  // step 1 → 2: 备份

        AgentAction deleteAction = AgentAction.of(
                "task-erp-delete", "DELETE", "删除 5 条测试订单",
                Map.of("count", 5, "env", "test"));
        interruptManager.interrupt(
                InterruptSignal.forApproval(execId, ctx.getCurrentStep(), deleteAction, "等主管审批"));

        // 关键：中断后立刻打检查点，把「WAITING_APPROVAL + step2 + 变量」持久化
        Checkpoint cp = checkpointManager.checkpoint(ctx);
        System.out.println("已保存检查点: " + cp.checkpointId() + " version=" + cp.version());

        // 💥 模拟进程重启：原来的 interruptManager / ctx 全部丢失
        // （store 假设是 Redis/PG，重启后数据还在）

        // ================== 「进程 B」：重启后从检查点还原 → 恢复 ==================
        CheckpointManager cmAfterRestart = new CheckpointManager(store);
        ExecutionContext restored = cmAfterRestart.restoreLatest(execId).orElseThrow();
        System.out.println("还原状态: " + restored.getState());        // WAITING_APPROVAL
        System.out.println("还原步号: " + restored.getCurrentStep());  // 2
        System.out.println("还原变量: " + restored.getVariable("orderCount")); // 5

        // 把还原出来的现场重新纳入新的中断管理器，即可继续走恢复流程
        // （生产中 register 会支持传入已有 ctx，此处示意语义）
        System.out.println("重启后成功恢复执行现场，可继续等待审批并 resume");
    }
}
```

### 4.3 打点策略：频率的权衡

- **打太少**：崩溃后丢失的进度多（回退到很早的检查点，白跑很多步）；
- **打太多**：I/O 开销大，拖慢执行。

工程实践的黄金法则：**在「不可逆动作」和「昂贵动作」前后打点**。删除、扣款、发送这类不可逆操作前必打点；查询这类可重放的廉价操作可以不打。

---

## 五、避坑指南 + 小结 + FAQ + 面试题

### 5.1 避坑指南（≥12 条）

1. **检查点必须不可变**：用 `record` + `Map.copyOf`。若检查点可变，「读档」就不再确定，事故复盘也无法信任存档。

2. **冻结的是「值」不是「引用」**：`capture()` 必须调 `snapshotVariables()` 拿只读副本。若直接持有原 Map 引用，执行现场后续一改，检查点跟着变，快照就废了。

3. **版本号必须单调递增且并发安全**：用 `AtomicLong`。若用普通 `long++`，并发打点会产生重复版本号，`findLatest` 取错。

4. **`findLatest` 要按版本号而非插入顺序取**：分布式存储下插入顺序不可靠，必须 `max(comparingLong(version))`。

5. **变量里不能放不可序列化的对象**：`Checkpoint.variables` 将来要落 Redis/PG，若放了数据库连接、线程、Lambda 这类不可序列化对象，持久化会炸。只放纯数据。

6. **打点时机要卡在动作边界**：在「不可逆动作前」打点。若在动作执行到一半打点，恢复后会重复执行半个动作，产生脏数据。

7. **还原后状态要与检查点一致**：`rebuild` 必须完整还原 state/currentStep/resumeStep/variables，任何一个漏掉，恢复出来的现场就是残缺的。

8. **检查点也要清理**：任务彻底结束（COMPLETED/ABORTED）后调 `clear()`，否则 Redis/PG 里会堆积无用检查点，撑爆存储。

9. **区分「最新」与「正确」**：`findLatest` 取的是最新版本，但如果最后一次打点发生在执行跑偏之后，最新反而是错的——这时要用 `rollbackTo` 回到更早的安全点。

10. **恢复不等于重放副作用**：从检查点恢复的是「状态」，不是「重新执行已完成的步骤」。已经删掉的数据不会因为恢复而回来——检查点保存进度，不做数据回滚。

11. **存储端口的实现要处理并发写**：多个执行、甚至同一执行的多次打点可能并发。内存实现用 `ConcurrentHashMap` + `CopyOnWriteArrayList`；Redis/PG 实现要用事务或原子操作。

12. **检查点 ID 要全局唯一**：本章用 `executionId + "-cp-" + version` 保证同一执行内唯一。若多执行共用 ID 空间，需加入更强的唯一性（如 UUID）。

13. **不要把检查点当「事务」用**：检查点保存的是「进度快照」，不具备数据库事务的 ACID。真正的数据一致性要靠业务层的事务或补偿机制。

### 5.2 小结

本章给 Chapter 04 的执行现场加上了「跨重启存活」的能力：

- `Checkpoint` 用不可变 `record` 冻结执行现场的最小充分状态；
- `CheckpointStore` 抽象存储端口，屏蔽内存/Redis/PG 差异；
- `CheckpointManager` 编排「打点（capture+save）」与「还原（findLatest+rebuild）」，并管理版本；
- 打点策略的核心是「在不可逆/昂贵动作前后打点」。

至此，HITL 的运行时基础设施（审批引擎、中断/恢复、检查点）已经完备。但我们的审批还只是「单级」的——现实中的高危操作往往需要**多人会签、逐级审批、还要处理超时**。这正是下一章 **Chapter 06 · Multi-Level Approval** 要攻克的。

### 5.3 FAQ

**Q1：Checkpoint 和数据库事务的区别是什么？**
A：事务保证「一组操作要么全成功要么全回滚」的原子性；检查点只保存「执行进度的快照」，不回滚任何已发生的副作用。恢复到检查点，只是让执行从那个进度点接着跑，不会把已删的数据找回来。二者解决的是不同层面的问题，常配合使用。

**Q2：应该多久打一次检查点？**
A：没有固定频率，而是按「动作性质」打。不可逆动作（删除、扣款）和昂贵动作（大批量处理）前必打；廉价可重放动作（查询）可不打。目标是「崩溃后重跑的代价最小」与「打点 I/O 开销可接受」之间的平衡。

**Q3：内存实现重启不也丢了吗？教学为什么用内存？**
A：是的，内存实现重启会丢——它只用于单机教学演示「机制怎么跑通」。因为有 `CheckpointStore` 端口抽象，生产环境换成 `RedisCheckpointStore` 或 `JpaCheckpointStore`，业务代码一行不改就能真正跨重启。这正是端口/适配器架构的价值。

**Q4：`rollbackTo` 回滚后，比目标版本更新的检查点还留着吗？**
A：本章实现只是「读回」某个版本重建现场，不删除更新的检查点（历史完整保留，便于审计）。是否清理更新版本由业务决定——若明确要「废弃之后的进度」，可在回滚后调用清理逻辑。

**Q5：variables 里如果数据量很大（比如查出 10 万条订单）怎么办？**
A：不要把大数据塞进检查点。检查点应存「指针/引用」而非「数据本身」——比如存一个 `queryResultId`，真实结果放对象存储或数据库，恢复时按 id 重新加载。检查点保持「小而精」，只存恢复所需的元数据。

### 5.4 面试题

1. **请设计一个支持「跨进程重启恢复」的任务执行框架，说明检查点机制的核心组件。**（考点：运行时对象 vs 不可变存档、存储端口抽象、capture/rebuild 双向转换）

2. **检查点为什么必须不可变？如果允许修改会带来什么问题?**（考点：读档确定性、审计可信度、并发安全）

3. **`capture()` 冻结变量时为什么要用 `Map.copyOf` 而不是直接持有引用?**（考点：冻结值而非引用、防止后续污染快照）

4. **检查点的版本号在并发场景下如何保证单调递增且不冲突?**（考点:AtomicLong、findLatest 按版本取而非插入顺序）

5. **打检查点的频率如何权衡?在什么时机打点最合理?**（考点:恢复代价 vs I/O 开销、不可逆/昂贵动作边界）

6. **从检查点恢复执行，已经执行过的「删除数据」这类副作用会被撤销吗?为什么?**（考点:检查点保存进度不回滚副作用、与事务的区别）

7. **如果 variables 里要保存 10 万条查询结果,你会怎么设计?**（考点:存指针不存数据、检查点小而精、大数据外置）

8. **为什么要把 CheckpointStore 设计成接口而不是直接写死内存/Redis?这体现了什么设计原则?**（考点:依赖倒置、端口适配器、可替换性、可测性）