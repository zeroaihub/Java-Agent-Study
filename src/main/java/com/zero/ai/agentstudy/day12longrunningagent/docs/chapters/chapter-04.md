# Chapter 04 · Checkpoint / Snapshot / Recovery

> 本章目标：讲透"打点—快照—崩溃恢复"这条 Long Running Agent 的生命线。给出 `Checkpoint`、`CheckpointStore`、`InMemoryCheckpointStore`、`CheckpointManager`、`RecoveryService` 五个完整可运行代码。

---

## 第一部分 · 为什么学

### 4.1 崩溃是常态，不是意外

在分布式与云原生环境里，进程随时可能死：Pod 被驱逐、节点宕机、发版滚动重启、OOM Kill……对 ChatBot 这不致命（重发请求即可），但对一个已经跑了 3 小时、完成了 8 个步骤的长任务，如果崩溃后只能"从头再来"，意味着：

- 3 小时白跑，浪费算力与时间。
- 已执行的副作用（如已发出的通知、已写入的数据）可能被重复执行。
- 用户体验灾难：明明快完成了，一崩溃全没了。

**Long Running Agent 的核心竞争力，就是"崩溃后能从断点续跑，而不是从头重来"。** 实现这一点的机制就是 Checkpoint + Recovery。

### 4.2 类比：游戏存档

Checkpoint 就是游戏里的"存档点"。你打到第 5 关存了个档，即使角色死了，也能从第 5 关读档继续，而不是回到第 1 关。区别只在于：游戏是玩家手动存档，Agent 是 Runtime 每完成一步自动存档。

- **Snapshot（快照）**：某一刻的完整进度定格。
- **Checkpoint（检查点）**：把 Snapshot 落到存储、形成一个可恢复的点。
- **Recovery（恢复）**：崩溃重启后，读回最近的 Checkpoint，还原现场。
- **Resume（续跑）**：从还原的 stepIndex 继续执行剩余步骤。

### 4.3 打点频率的权衡

打点越频繁，崩溃时丢失的进度越少，但存储与性能开销越大；打点越稀疏，开销越小，但崩溃时可能回退更多。工程上的黄金实践是：**每完成一个 Step 打一次点**。这样崩溃时最多只丢"当前正在执行的这一步"，粒度刚好、开销可控。

---

## 第二部分 · 是什么

### 4.4 打点与恢复的完整时序

```
正常执行：
  Step0 完成 ──► snapshot(stepIndex=1) ──► 存 Checkpoint
  Step1 完成 ──► snapshot(stepIndex=2) ──► 存 Checkpoint
  Step2 执行中 ✗ 进程崩溃！（stepIndex 仍是 2 的检查点）

重启恢复：
  RecoveryService.recoverAll()
    │
    ├─ findByStates(活跃态) ──► 捞出崩溃前未完成的 Session
    │
    ├─ CheckpointManager.restore() ──► 读回最近 Checkpoint(stepIndex=2)
    │                                   还原 Context
    │
    └─ resume 回调 ──► Runtime 从 Step2 继续执行（不重跑 Step0/Step1）
```

关键：崩溃发生在 Step2 执行中，但最近检查点是"Step1 完成后（stepIndex=2）"，所以恢复后从 Step2 重新开始——Step0/Step1 不会重跑。

### 4.5 恢复的边界：哪些状态该自动续跑？

不是所有活跃态都该自动续跑：

| 状态 | 自动恢复？ | 原因 |
| --- | --- | --- |
| CREATED | 是 | 刚建未跑，直接开跑 |
| RUNNING | 是 | 崩溃时正在跑，续跑 |
| RETRYING | 是 | 重试等待中，继续重试 |
| WAITING | 是 | 等定时，恢复调度 |
| SUSPENDED | **否** | 在等外部事件（如人工审批），不能自动跑，必须等事件到达才 resume |

这就是 `RecoveryService.ACTIVE_STATES` **故意不含 SUSPENDED** 的原因——把"等定时"和"等审批"用不同状态区分（Ch02 的设计）在这里兑现了价值。

### 4.6 底层原理：为什么 Checkpoint 必须不可变

Checkpoint 是"某一刻的定格"。如果它持有对 Context 内部 Map 的引用，那么 Context 后续被修改时，Checkpoint 也会跟着变——快照就不再是快照了。因此 `Checkpoint` 所有字段 `final`，attributes 做 `Map.copyOf` 防御性拷贝，与源 Context 彻底隔离。这是"不可变对象 = 天然线程安全 + 语义正确"的经典应用。

---

## 第三部分 · 怎么用（完整可运行 Java 代码）

### 4.7 Checkpoint（不可变快照）

文件：`day12longrunningagent/checkpoint/Checkpoint.java`

```java
package com.zero.ai.agentstudy.day12longrunningagent.checkpoint;

import java.time.Instant;
import java.util.Map;

public final class Checkpoint {

    private final String sessionId;
    private final int stepIndex;
    private final int retryCount;
    private final Map<String, Object> attributes;   // 防御性拷贝，与源 Context 隔离
    private final Instant createdAt;

    public Checkpoint(String sessionId, int stepIndex, int retryCount, Map<String, Object> attributes) {
        this.sessionId = sessionId;
        this.stepIndex = stepIndex;
        this.retryCount = retryCount;
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        this.createdAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public int getStepIndex() { return stepIndex; }
    public int getRetryCount() { return retryCount; }
    public Map<String, Object> getAttributes() { return attributes; }
    public Instant getCreatedAt() { return createdAt; }
}
```

### 4.8 CheckpointStore 接口与内存实现

文件：`day12longrunningagent/checkpoint/CheckpointStore.java`

```java
public interface CheckpointStore {
    void append(Checkpoint checkpoint);                    // 追加检查点
    Optional<Checkpoint> findLatest(String sessionId);     // 恢复时取最近一个
    List<Checkpoint> findAll(String sessionId);            // 全部历史（审计/回滚）
    void deleteBySession(String sessionId);
}
```

文件：`day12longrunningagent/checkpoint/InMemoryCheckpointStore.java`

```java
@Component
public class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentHashMap<String, List<Checkpoint>> store = new ConcurrentHashMap<>();

    @Override public void append(Checkpoint cp) {
        store.computeIfAbsent(cp.getSessionId(), k -> new CopyOnWriteArrayList<>()).add(cp);
    }
    @Override public Optional<Checkpoint> findLatest(String sessionId) {
        List<Checkpoint> list = store.get(sessionId);
        if (list == null || list.isEmpty()) return Optional.empty();
        return Optional.of(list.get(list.size() - 1));
    }
    @Override public List<Checkpoint> findAll(String sessionId) {
        List<Checkpoint> list = store.get(sessionId);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }
    @Override public void deleteBySession(String sessionId) { store.remove(sessionId); }
}
```

**要点**：保留检查点历史（`List` 而非单值），支持审计与"回滚到任意点"；`CopyOnWriteArrayList` 适合"读多写少 + 并发安全"的检查点场景。

### 4.9 CheckpointManager（打点 / 还原）

文件：`day12longrunningagent/checkpoint/CheckpointManager.java`

```java
@Service
public class CheckpointManager {

    private final CheckpointStore checkpointStore;

    public CheckpointManager(CheckpointStore checkpointStore) {
        this.checkpointStore = checkpointStore;
    }

    /** 打点：把当前 Context 定格为 Checkpoint 并存储。 */
    public Checkpoint snapshot(AgentSession session) {
        AgentContext ctx = session.getContext();
        Checkpoint cp = new Checkpoint(session.getSessionId(),
                ctx.getStepIndex(), ctx.getRetryCount(), ctx.getAttributes());
        checkpointStore.append(cp);
        return cp;
    }

    /** 还原：从最近 Checkpoint 恢复 Context（无检查点返回 false）。 */
    public boolean restore(AgentSession session) {
        Optional<Checkpoint> latest = checkpointStore.findLatest(session.getSessionId());
        if (latest.isEmpty()) return false;
        Checkpoint cp = latest.get();
        AgentContext ctx = session.getContext();
        ctx.setStepIndex(cp.getStepIndex());
        ctx.setRetryCount(cp.getRetryCount());
        ctx.getAttributes().clear();
        ctx.getAttributes().putAll(cp.getAttributes());
        return true;
    }
}
```

### 4.10 RecoveryService（崩溃恢复）

文件：`day12longrunningagent/recovery/RecoveryService.java`

```java
@Service
public class RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryService.class);

    // 故意不含 SUSPENDED：等外部事件的任务不能自动续跑
    private static final AgentState[] ACTIVE_STATES = {
            AgentState.CREATED, AgentState.RUNNING,
            AgentState.RETRYING, AgentState.WAITING
    };

    private final SessionStore sessionStore;
    private final CheckpointManager checkpointManager;

    public RecoveryService(SessionStore sessionStore, CheckpointManager checkpointManager) {
        this.sessionStore = sessionStore;
        this.checkpointManager = checkpointManager;
    }

    /** 恢复所有活跃态 Session；resume 回调由 Runtime 提供续跑逻辑（依赖倒置，避免循环依赖）。 */
    public int recoverAll(Consumer<AgentSession> resume) {
        List<AgentSession> actives = sessionStore.findByStates(ACTIVE_STATES);
        log.info("[Recovery] 发现 {} 个活跃态 Session 需要恢复", actives.size());
        int recovered = 0;
        for (AgentSession session : actives) {
            try {
                boolean restored = checkpointManager.restore(session);
                log.info("[Recovery] sessionId={} restoredFromCheckpoint={} stepIndex={}",
                        session.getSessionId(), restored, session.getContext().getStepIndex());
                resume.accept(session);
                recovered++;
            } catch (Exception e) {
                log.error("[Recovery] 恢复 {} 失败: {}", session.getSessionId(), e.getMessage(), e);
            }
        }
        return recovered;
    }
}
```

**架构要点**：`RecoveryService` 不直接依赖 `AgentRuntime`（否则 Runtime→Recovery→Runtime 循环依赖），而是用 `Consumer<AgentSession>` 回调把"续跑动作"注入进来。这是**依赖倒置原则**的教科书用法——高层模块与低层模块都依赖抽象（函数式接口），而非彼此。

### 4.11 使用示例

```java
// 打点（Runtime 在每步完成后调用）
checkpointManager.snapshot(session);

// 崩溃重启后，在启动钩子里恢复
recoveryService.recoverAll(session -> {
    // 由 Runtime 提供：重新把 session 投入执行队列续跑
    runtime.resume(session);
});
```

---

## 第四部分 · 真实项目

- **Temporal / Cadence**：用"事件溯源 + 确定性重放"实现恢复——记录每个决策事件，重启后重放事件到崩溃点，等价于超细粒度 Checkpoint。
- **Apache Flink**：分布式流处理的 Checkpoint 机制（Chandy-Lamport 分布式快照算法），周期性对算子状态打点，故障后从最近 Checkpoint 恢复，保证 Exactly-Once。
- **Spark**：RDD checkpoint + lineage，节点失败后可从检查点重算。
- **数据库**：WAL（Write-Ahead Log）+ Checkpoint 是崩溃恢复的经典组合，思想完全一致。

这些系统都印证：**Checkpoint/Recovery 是一切"必须活很久、不能丢进度"系统的通用范式。**

---

## 第五部分 · 避坑

1. **坑：Checkpoint 持有 Context 内部 Map 引用。** 快照被后续修改污染。→ `Map.copyOf` 防御性拷贝，字段全 `final`。
2. **坑：只在任务结束时打一次点。** 崩溃即全丢。→ 每完成一步打一次点。
3. **坑：恢复时把 SUSPENDED 也自动续跑。** 绕过了人工审批，产生越权副作用。→ SUSPENDED 排除在自动恢复外。
4. **坑：恢复后从 stepIndex=0 重跑。** 已执行的副作用重复。→ 从 Checkpoint 的 stepIndex 续跑。
5. **坑：副作用步骤（发消息/扣款）不做幂等。** 崩溃在"已执行副作用、未打点"之间时，续跑会重复副作用。→ 副作用步骤必须幂等（Ch08 详述）。
6. **坑：RecoveryService 直接依赖 Runtime。** 循环依赖、启动失败。→ 用 `Consumer` 回调依赖倒置。
7. **坑：只存最后一个 Checkpoint，覆盖历史。** 无法审计、无法回滚到更早点。→ 保留检查点历史列表。
8. **坑：恢复时不 catch 单个 Session 异常。** 一个坏 Session 拖垮整个恢复流程。→ 逐个 try-catch，隔离失败。
9. **坑：恢复没有日志。** 线上出问题无从排查。→ 每个 Session 的恢复结果都打结构化日志（含 sessionId）。
10. **坑：打点与状态 save 不在一起。** 打点了但状态没落库，或反之，二者不一致。→ Runtime 里把"改状态 + save + snapshot"作为一致的动作序列（Ch08）。

---

## 本章小结

- 崩溃是常态，Long Running Agent 靠 Checkpoint/Recovery 实现"断点续跑"而非"从头重来"。
- 打点频率黄金实践：每完成一个 Step 打一次点。
- Checkpoint 必须不可变（final + 防御性拷贝），才是真正的"快照"。
- Recovery 只自动续跑真正的活跃态，SUSPENDED（等外部事件）不自动跑。
- 用 `Consumer` 回调让 Recovery 与 Runtime 依赖倒置，避免循环依赖。

### 面试题

1. Checkpoint / Snapshot / Recovery / Resume 各是什么，如何配合？
2. 为什么 Checkpoint 必须是不可变对象？
3. 打点频率如何权衡？工程黄金实践是什么？
4. 为什么 SUSPENDED 状态不纳入自动恢复？
5. RecoveryService 为什么用回调而不直接依赖 Runtime？

### 扩展阅读

- Chandy-Lamport 分布式快照算法
- Apache Flink Checkpointing 文档
- 数据库 WAL 与 Checkpoint 机制

---

> ✅ 本章完成。请输入"**继续**"，进入 Chapter 05：调度、任务队列、重试与死信队列（Scheduler / TaskQueue / Retry / DLQ，含完整 Java 代码）。