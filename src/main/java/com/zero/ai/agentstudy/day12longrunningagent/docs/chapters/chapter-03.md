# Chapter 03 · Session 与 State 持久化

> 本章目标：理解"长任务实例"如何被建模为 Session，运行进度如何被建模为 Context，以及为什么必须把状态"外置"到 Store。给出 `AgentSession`、`AgentContext`、`SessionStore`、`InMemorySessionStore` 四个完整可运行代码。

---

## 第一部分 · 为什么学

### 3.1 长任务需要"身份"

一个 ChatBot 请求是"用完即弃"的，不需要身份。但一个长任务——比如"每天9点抓 GitHub Trending"——它会跨越数小时、跨越进程重启、甚至跨越多个节点。此时必须回答：

- **这是哪个任务？** → 需要一个全局唯一的 `sessionId`。
- **它现在到哪一步了？** → 需要一个进度指针 `stepIndex`。
- **它中途产生了什么中间结果？** → 需要一个中间产物容器。
- **它现在是什么状态？** → 需要 `AgentState`。
- **它归哪个节点管？还活着吗？** → 需要 `owner` 与 `heartbeat`。

把这些聚合起来，就是 **Session（会话）**。Session 是长任务的"身份证 + 病历本"。

### 3.2 为什么状态必须"外置"

新手最容易犯的错：把状态存在内存变量里。

```java
// 反面教材
Map<String, TaskState> runningTasks = new HashMap<>(); // 只在内存
```

进程一崩，这个 Map 灰飞烟灭，所有正在跑的长任务全部丢失、无法恢复。这在 ChatBot 里无所谓（请求本就短命），但长任务的价值恰恰在于"能活很久"，内存态与它的使命根本矛盾。

**解决之道：状态外置（State Externalization）。** 权威状态不放在进程内存，而是每次变更都落到外部存储（Store）。进程只是"临时借用"状态来干活，随时可以被替换。这就是 Kubernetes 里 Pod 可随意重启、Temporal 里 Worker 可随意扩缩容的根本前提——**无状态的执行体 + 外置的权威状态**。

### 3.3 冷热分层：Redis + PostgreSQL

生产环境通常分两层：

- **热数据（Redis）**：高频读写的运行态（当前 state、stepIndex、心跳），要求低延迟。
- **冷/持久数据（PostgreSQL）**：需要长期保存、可审计的历史（Session 归档、执行日志、Checkpoint 历史），要求强持久与可查询。

本课程用 `InMemorySessionStore` 作默认实现（零依赖、可直接跑），但通过 `SessionStore` 接口把两层抽象统一，替换实现时上层代码零改动。

---

## 第二部分 · 是什么

### 3.4 三层数据模型

```
┌──────────────────────────────────────────────┐
│                AgentSession                    │  ← 长任务实例（身份+状态+租约+时间戳）
│  sessionId / agentType / state / owner / hb    │
│  ┌──────────────────────────────────────────┐ │
│  │            AgentContext                    │ │  ← 运行上下文（进度+中间产物）
│  │  stepIndex / retryCount / attributes(KV)   │ │
│  └──────────────────────────────────────────┘ │
└──────────────────────────────────────────────┘
                    │ save / findById / findByStates
                    ▼
┌──────────────────────────────────────────────┐
│               SessionStore (接口)               │  ← 状态外置抽象
│   InMemory  |  Redis(热)  |  PostgreSQL(冷)     │
└──────────────────────────────────────────────┘
```

- **Session** 聚合 Context，是持久化的最小单元。
- **Context** 是恢复的核心载体：`stepIndex` 决定"从哪续跑"，`attributes` 保存步骤间传递的数据。
- **Store** 是状态外置的落点，面向接口，实现可插拔。

### 3.5 底层原理：为什么 Context 只存"可序列化数据"

Context 会被写入 Store（未来是 Redis/PG），因此它承载的东西必须能被序列化、能跨进程恢复。绝不能往里塞数据库连接、线程、文件句柄这类"运行时活对象"——它们无法序列化，恢复后也失效。这是"恢复所必需的最小数据"原则。

---

## 第三部分 · 怎么用（完整可运行 Java 代码）

### 3.6 AgentContext

文件：`day12longrunningagent/session/AgentContext.java`

```java
package com.zero.ai.agentstudy.day12longrunningagent.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AgentContext {

    private int stepIndex;                                              // 进度指针（0 基）
    private final Map<String, Object> attributes = new ConcurrentHashMap<>(); // 中间产物 KV
    private int retryCount;                                             // 本轮已重试次数

    public int getStepIndex() { return stepIndex; }
    public void setStepIndex(int stepIndex) { this.stepIndex = stepIndex; }
    public int advance() { return ++this.stepIndex; }                  // 推进到下一步

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int incrementRetry() { return ++this.retryCount; }
    public void resetRetry() { this.retryCount = 0; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void put(String key, Object value) { attributes.put(key, value); }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) { return (T) attributes.get(key); }
    public boolean has(String key) { return attributes.containsKey(key); }
}
```

**要点**：`attributes` 用 `ConcurrentHashMap` 保证并发安全；`advance()` / `incrementRetry()` 把"推进"封装为语义方法，避免调用方裸操作 `stepIndex++` 出错。

### 3.7 AgentSession

文件：`day12longrunningagent/session/AgentSession.java`

```java
package com.zero.ai.agentstudy.day12longrunningagent.session;

import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentState;
import java.time.Instant;
import java.util.UUID;

public class AgentSession {

    private final String sessionId;      // 全局唯一，充当 TraceId
    private final String agentType;      // 决定用哪套 Step 执行
    private volatile AgentState state;   // 当前状态（变更须经状态机校验）
    private final AgentContext context;  // 运行上下文
    private volatile String lastError;   // 最近失败原因
    private volatile Instant lastHeartbeat; // 心跳（租约判活）
    private volatile String owner;       // 租约拥有者节点
    private final Instant createdAt;
    private volatile Instant updatedAt;

    public AgentSession(String agentType) { this(UUID.randomUUID().toString(), agentType); }

    public AgentSession(String sessionId, String agentType) {
        this.sessionId = sessionId;
        this.agentType = agentType;
        this.state = AgentState.CREATED;
        this.context = new AgentContext();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.lastHeartbeat = this.createdAt;
    }

    public String getSessionId() { return sessionId; }
    public String getAgentType() { return agentType; }
    public AgentState getState() { return state; }

    /** 内部写入：外部禁止直调，必须经 Runtime + StateMachine.transit 校验。 */
    public void setState(AgentState state) { this.state = state; touch(); }

    public AgentContext getContext() { return context; }
    public String getLastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e; touch(); }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void heartbeat() { this.lastHeartbeat = Instant.now(); }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    private void touch() { this.updatedAt = Instant.now(); }
}
```

**要点**：状态字段 `volatile` 保证多线程可见性；`setState` 注释明确"禁止外部直调"，强约束通过 Runtime 落地（Ch08）；`heartbeat()` 为多节点租约判活预留。

### 3.8 SessionStore 接口

文件：`day12longrunningagent/state/SessionStore.java`

```java
package com.zero.ai.agentstudy.day12longrunningagent.state;

import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentState;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import java.util.List;
import java.util.Optional;

public interface SessionStore {
    void save(AgentSession session);                       // upsert
    Optional<AgentSession> findById(String sessionId);
    List<AgentSession> findByStates(AgentState... states); // Recovery 扫描活跃态
    List<AgentSession> findAll();
    void delete(String sessionId);
    long count();
}
```

**要点**：`findByStates` 是 Recovery 的关键——崩溃后正是靠它捞出所有非终态 Session 逐个恢复。

### 3.9 InMemorySessionStore 实现

文件：`day12longrunningagent/state/InMemorySessionStore.java`

```java
package com.zero.ai.agentstudy.day12longrunningagent.state;

import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentState;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class InMemorySessionStore implements SessionStore {

    private final ConcurrentHashMap<String, AgentSession> store = new ConcurrentHashMap<>();

    @Override public void save(AgentSession session) { store.put(session.getSessionId(), session); }
    @Override public Optional<AgentSession> findById(String id) { return Optional.ofNullable(store.get(id)); }

    @Override public List<AgentSession> findByStates(AgentState... states) {
        Set<AgentState> target = states == null || states.length == 0
                ? EnumSet.noneOf(AgentState.class) : EnumSet.copyOf(Arrays.asList(states));
        return store.values().stream()
                .filter(s -> target.contains(s.getState()))
           .collect(Collectors.toList());
    }

    @Override public List<AgentSession> findAll() { return List.copyOf(store.values()); }
    @Override public void delete(String id) { store.remove(id); }
    @Override public long count() { return store.size(); }
}
```

**要点**：`@Component` 让 Spring 默认注入这个实现；换成 Redis/PG 实现时，只需给新实现加 `@Primary` 或用 `@ConditionalOnProperty` 切换，上层零改动。

### 3.10 使用示例

```java
SessionStore store = new InMemorySessionStore();

AgentSession session = new AgentSession("github-trending");
session.getContext().put("date", "2026-07-30");
store.save(session);

// 崩溃恢复：捞出所有活跃态
List<AgentSession> active = store.findByStates(
        AgentState.RUNNING, AgentState.RETRYING,
        AgentState.SUSPENDED, AgentState.WAITING, AgentState.CREATED);
```

---

## 第四部分 · 真实项目

- **Temporal**：Workflow 的状态由 Server 持久化到 Cassandra/PostgreSQL，Worker 完全无状态，可随意扩缩容——正是"状态外置 + 无状态执行体"。
- **Netflix Conductor**：Workflow/Task 状态存于 Redis + 持久化存储，与本章分层一致。
- **Kubernetes Operator**：期望状态存于 etcd（外置），控制器进程崩溃重启后从 etcd 恢复，不丢状态。
- **电商订单**：订单状态存 DB 而非应用内存，应用可无限水平扩展。

### 3.11 与消息队列的关系

Session 外置后，"下一步该谁来执行"可以由队列（Ch05）派发。执行体从队列取任务、从 Store 读状态、执行、写回 Store——这是可水平扩展的长任务架构基本盘。

---

## 第五部分 · 避坑

1. **坑：把状态存进程内存。** 崩溃即丢。→ 一切权威状态外置到 Store。
2. **坑：Context 里塞不可序列化对象（连接/线程）。** 无法持久化与恢复。→ 只存可序列化的最小必要数据。
3. **坑：`sessionId` 用自增数字或业务字段。** 多节点冲突、可猜测。→ 用 UUID。
4. **坑：状态变更后忘记 `store.save`。** 内存改了、存储没改，崩溃后回到旧态。→ Runtime 里"改状态 + save"原子封装（Ch08）。
5. **坑：`findByStates` 漏掉某个活跃态。** Recovery 捞不全，部分任务永久卡死。→ 明确枚举所有活跃态。
6. **坑：Store 实现非线程安全。** 并发写覆盖。→ 用 `ConcurrentHashMap` 或 DB 事务。
7. **坑：直接把内部 Map 引用返回给外部。**被篡改。→ `List.copyOf` 返回不可变拷贝。
8. **坑：Session 字段非 `volatile` 却多线程读写。** 可见性问题。→ 可变共享字段加 `volatile`。
9. **坑：把 `state` 和 `stepIndex` 分别存不同地方。** 二者不一致时恢复错乱。→ 同属一个 Session，一起 save。
10. **坑：一开始就上 Redis+PG，本地无法跑。** 学习/测试门槛高。→ 先用内存实现跑通，再面向接口替换。

---

## 本章小结

- Session = 长任务的身份 + 状态 + 租约 + 上下文，是持久化最小单元。
- Context = 进度指针 + 中间产物，是恢复的核心载体，只存可序列化数据。
- Store = 状态外置抽象，内存实现零依赖跑通，生产替换 Redis+PG 零改动上层。
- 状态外置是"无状态执行体 + 可水平扩展"的根本前提。

### 面试题

1. 为什么长任务的状态必须外置？内存态有什么致命问题？
2. Redis 与 PostgreSQL 在状态存储中各承担什么角色？
3. Context 里为什么不能存数据库连接？
4. Recovery 如何用 `findByStates` 找到需要恢复的任务？
5. 面向 `SessionStore` 接口编程带来了什么好处？

### 扩展阅读

- Temporal 架构文档：Persistence 层设计
- 《Designing Data-Intensive Applications》第 3、5 章
- Redis 持久化（RDB/AOF）与 PostgreSQL WAL

---

> ✅ 本章完成。请输入"**继续**"，进入 Chapter 04：Checkpoint / Snapshot / Recovery（打点、快照与崩溃恢复，含完整 Java 代码）。