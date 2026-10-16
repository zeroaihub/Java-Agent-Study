# 第 1 章 · knowledge-core 领域内核

> 「所有伟大的架构，都始于对『不变量』的敬畏。」
>
> 本章我们不写任何业务逻辑，而是为整个企业知识平台锻造最底层、最稳定、被所有模块依赖的**领域内核（core）**。它没有依赖任何框架，是整个六边形架构最内圈的「纯净核心」。

---

## 一、学习目标

学完本章，你将能够：

1. 理解 **Clean/Hexagonal 架构** 为什么要有一个「零框架依赖的内核」，以及它如何决定整个系统的依赖方向。
2. 掌握 **DDD 战术建模**中的三类基础构件：**值对象（Value Object）**、**强类型 ID（Typed ID）**、**领域事件（Domain Event）**。
3. 学会用 Java 21 的 `record` 构建**不可变值对象**，并在紧凑构造器中内建校验（fail-fast）。
4. 理解**多租户 SaaS** 的第一道防线：租户上下文（TenantContext）与租户强类型 ID。
5. 建立一套**统一结果 / 统一错误码 / 统一异常**的工程规范，杜绝散乱的 `try-catch` 与裸抛异常。

---

## 二、为什么需要一个「领域内核」

初学者写 RAG Demo 时，往往把所有东西塞进一个 `Service`：解析、切分、向量化、检索，全靠 `String docId`、`Map<String,Object>` 满天飞。这在 200 行的 Demo 里没问题，但当系统膨胀到 14 个模块、多人协作、多租户隔离时，会立刻暴露三个致命问题：

| 问题 | Demo 写法的后果 | core 内核的解法 |
| --- | --- | --- |
| **ID 混用** | `String docId` 和 `String chunkId` 可以互相传参，编译器不报错，线上却查错数据 | 强类型 ID（`DocumentId` / `ChunkId`），传错直接编译失败 |
| **租户泄漏** | 忘记带 `tenantId`，A 租户查到 B 租户的文档 | `TenantContext` + `TenantId` 强制租户在场 |
| **错误处理混乱** | 每层各自 `catch` + 返回 `null` / 抛 `RuntimeException` | 统一 `KnowledgeException` + `KnowledgeErrorCode` + `KnowledgeResult` |

> **核心原则：依赖倒置（DIP）。** 内核不依赖任何外部模块，反而是所有外部模块依赖内核。这样内核最稳定，改动最少，是整个系统的「地基」。

依赖方向如下（箭头表示「依赖于」）：

```
document / parser / chunk / embedding / retrieval / workspace ...
                          │
                          ▼
                   knowledge-core   ← 最稳定，零框架依赖
```

---

## 三、代码逐行精讲

### 3.1 强类型 ID：让编译器替你抓 Bug

```java
public record DocumentId(String value) {
    public DocumentId {
        KnowledgeException.requireNonNull(value, "documentId");
    }
    public static DocumentId of(String value) { return new DocumentId(value); }
    public static DocumentId newId() { return new DocumentId("doc-" + UUID.randomUUID()); }
    @Override public String toString() { return value; }
}
```

**设计要点：**

- 用 `record` 一行获得不可变性 + `equals`/`hashCode`，值语义天然正确。
- **紧凑构造器**（`public DocumentId {}`）做空校验，任何非法 ID 在创建瞬间就失败（fail-fast），而不是等到查数据库才炸。
- `newId()` 带业务前缀（`doc-` / `ck-` / `ws-` / `kb-`），肉眼即可辨认 ID 归属，排查线上日志时极其省心。
- **为什么不用 `String`？** 因为 `move(String from, String to)` 这种签名，调用方极易传反；而 `move(DocumentId from, WorkspaceId to)` 传反就编译不过。这是**用类型系统把运行时错误提前到编译期**。

### 3.2 TenantContext：多租户的第一道闸门

```java
public static <T> T runWithTenant(TenantId tenantId, Supplier<T> action) {
    TenantId previous = HOLDER.get();
    try {
        HOLDER.set(tenantId);
        return action.get();
    } finally {
        if (previous == null) HOLDER.remove(); else HOLDER.set(previous);
    }
}
```

**设计要点：**

- 基于 `ThreadLocal`，天然适配 Java 21 **虚拟线程**（每个请求一个虚拟线程，互不干扰）。
- `runWithTenant` 是**模板方法**：进入前 `set`，`finally` 里恢复/清理，**从根本上杜绝忘记 `clear` 导致的租户泄漏**——这是多租户系统最凶险的 Bug 之一。
- `require()` 在租户缺失时直接抛 `TENANT_REQUIRED`，让「无租户访问」成为不可能事件。

### 3.3 领域事件：为事件驱动埋下伏笔

```java
public abstract class AbstractDomainEvent implements DomainEvent {
    protected AbstractDomainEvent(TenantId tenantId) {
        this.eventId = UUID.randomUUID().toString();
        this.tenantId = KnowledgeException.requireNonNull(tenantId, "tenantId");
        this.occurredAt = Instant.now();
    }
}
```

**设计要点：**

- 每个事件天生带 `eventId`（幂等）、`tenantId`（租户不丢）、`occurredAt`（可追溯）。
- 文档上传成功 → `DocumentIngestedEvent` → 解析/切分/向量化异步消费；文档删除 → `DocumentDeletedEvent` → 级联删除 Chunk 与向量。**事件是模块解耦的粘合剂**，后续章节会大量使用。

### 3.4 统一结果 / 错误码 / 异常「铁三角」

- `KnowledgeErrorCode`：按模块分段（`KP-0xxx` 通用 / `KP-1xxx` 文档 / `KP-2xxx` 解析 …），错误码即「路由表」，一眼定位归属模块。
- `KnowledgeException`：所有可预期业务异常的唯一载体，配套 `of()` / `wrap()` / `requireNonNull()`。
- `KnowledgeResult<T>`：统一出参，携带 `traceId`（从 MDC 读取），前后端联调、链路追踪一步到位。

---

## 四、架构设计思想

1. **稳定依赖原则（SDP）**：越被依赖的模块越应该稳定。core 被所有人依赖，所以它零框架依赖、极少变更。
2. **值对象 vs 实体**：ID、Metadata、Page 都是值对象（无生命周期、值相等）；Document、Chunk 是实体（有 ID、有生命周期），下一章登场。
3. **fail-fast**：非法状态在构造瞬间暴露，而非在下游某个深处炸裂——这是可维护性的关键。

---

## 五、企业实战案例

### 案例：某券商知识中台的「串租户」事故复盘

某券商早期知识库用 `String tenantId` 手动透传。一次重构中，异步线程池复用了带有上一个请求 `ThreadLocal` 的线程，且忘记 `clear`，导致 **A 客户经理搜到了 B 团队的尽调报告**，触发合规红线。

**根因**：`ThreadLocal` 手动 `set` 却依赖开发者记得 `finally { clear() }`，人性不可靠。

**本章解法**：把「set → 执行 → 恢复」封装进 `runWithTenant` 模板方法，开发者无从遗漏；配合虚拟线程「一请求一线程」，从架构上消灭该类事故。

```java
KnowledgeResult<Page<DocumentSummary>> result =
    TenantContext.runWithTenant(TenantId.of("acme"), () -> documentQuery.list(pageRequest));
// 出了这个 lambda，租户上下文自动清理，绝不泄漏到下一个任务
```

---

## 六、面试高频题

1. **强类型 ID 相比 `String` / `Long` 有什么收益？代价是什么？**
   收益：编译期防混用、语义自解释、便于承载校验；代价：轻微样板代码（`record` 已极大缓解）、序列化需适配（Jackson 需注册或用 `@JsonValue`）。

2. **`ThreadLocal` 在虚拟线程下还安全吗？为什么？**
   安全。虚拟线程仍是「一任务一线程」的隔离模型，`ThreadLocal` 语义不变；但因虚拟线程海量创建，应避免用 `ThreadLocal` 缓存大对象。生产可考虑 `ScopedValue`（结构化并发）作为进阶替代。

3. **为什么内核要「零框架依赖」？违反会怎样？**
   为了稳定与可测试。若内核依赖 Spring，则单测要起容器、升级框架会波及内核、内核难以复用到非 Spring 环境。违反会让「最稳定的地基」变成「最易碎的耦合点」。

4. **值对象为什么必须不可变？**
   不可变对象天然线程安全、可安全共享、`hashCode` 稳定（可安全做 Map key），且杜绝「共享可变状态」引发的隐蔽 Bug。

---

## 七、扩展阅读

- 《领域驱动设计》Eric Evans —— 值对象、实体、领域事件的经典定义。
- 《实现领域驱动设计》Vaughn Vernon —— 战术建模落地细节。
- Robert C. Martin,《Clean Architecture》—— 依赖规则与稳定依赖原则。
- JEP 444 Virtual Threads、JEP 446 Scoped Values —— Java 21+ 并发上下文传递的现代方案。

---

## 八、本章小结

本章我们为整个平台打好了地基：

- **强类型 ID**（Tenant/Workspace/KnowledgeBase/Document/Chunk）把 ID 混用挡在编译期。
- **TenantContext** 用模板方法根治租户泄漏，天然适配虚拟线程。
- **DomainEvent / AbstractDomainEvent** 为后续事件驱动、级联删除铺路。
- **结果 / 错误码 / 异常铁三角** 建立统一工程规范。
- **Page / PageRequest / Metadata** 作为通用值对象服务全局。

> 下一章预告：我们将产出 **ARCHITECTURE.md**，从宏观俯瞰 14 个模块的边界、依赖与数据流，正式进入整体架构设计。

——回复「继续」进入第 2 章。