# 第 3 章 · knowledge-document：文档聚合与生命周期状态机

> 「状态不是字段，是契约。」
>
> 第 1 章我们锻造了内核，第 2 章画好了地图。本章开始真正落地第一个业务模块：
> **文档域**。它是整条 Ingestion 流水线的「主语」——解析、切分、向量化、检索，
> 全都是在问同一个问题：**这份文档现在怎么样了？**

---

## 一、学习目标

学完本章，你将能够：

1. 理解 **实体（Entity）与值对象（Value Object）的分界线**：为什么 `TenantId` 不可变，而 `Document` 必须可变。
2. 用 **状态机（State Machine）** 建模文档生命周期，杜绝「非法状态跳转」这类线上事故。
3. 理解 **聚合根守护不变量** 的三条具体做法：租户不可变、状态只走状态机、原文可追溯。
4. 掌握 **版本号建模**：为什么「文档更新」= 生成新版本 + 重跑流水线，而不是原地改内容。
5. 学会用 **领域事件** 串联流水线阶段，为第 4、5 章的解析/切分与后续的删除同步铺路。

---

## 二、为什么「文档」值得单独一个模块

新手做 RAG，通常把文档简化成一行代码：

```java
String text = Files.readString(path);
```

这在 Demo 里没问题，一旦进企业就会连续踩四个坑：

| 坑 | 后果 | 本章的解法 |
| --- | --- | --- |
| **没有状态** | 解析失败了没人知道，文档永远卡在半路，成了僵尸数据 | `DocumentStatus` + 状态机，任何一步失败都会落到 `FAILED` |
| **没有版本** | 文档改了，旧向量还在库里，检索到过期知识 | `DocumentVersion`，更新即生成新版本并触发重跑 |
| **没有租户校验** | 一个漏写的 where 条件，A 公司看到 B 公司的合同 | 聚合根 `assertBelongsTo()` 兜底 |
| **没有事件** | 删除文档时忘了删向量，出现「幽灵知识」 | `DocumentDeletedEvent`，级联清理的触发器 |

> **一句话**：文档不是一个文件，而是一条**有状态、有版本、有归属、有历史**的业务对象。

---

## 三、代码逐行精讲

本章新增代码位于 `day15knowledgeplatform/document/`：

```
document/
├── DocumentStateMachine.java          状态机（转移表）
├── DocumentRepository.java            仓储端口
├── InMemoryDocumentRepository.java    内存适配器
├── DocumentService.java               应用服务（4 个用例）
├── model/
│   ├── Document.java                  聚合根
│   ├── DocumentStatus.java            生命周期状态
│   ├── DocumentFormat.java            格式枚举
│   └── DocumentVersion.java           版本号值对象
└── event/
    ├── DocumentUploadedEvent.java
    ├── DocumentStatusChangedEvent.java
    └── DocumentDeletedEvent.java
```

### 3.1 DocumentStatus：把「进行中」也建模成状态

```java
UPLOADED("已上传", false),
PARSING("解析中", false),
PARSED("已解析", false),
CHUNKING("切分中", false),
CHUNKED("已切分", false),
EMBEDDING("向量化中", false),
EMBEDDED("已向量化", false),
INDEXED("已入库", true),
FAILED("处理失败", true),
DELETED("已删除", true);
```

**为什么每个阶段都要拆成「进行中 + 已完成」两个状态，而不是只留「已完成」？**

因为流水线是异步的。如果只有 `PARSED`，那么「正在解析」和「解析完了」无法区分，带来三个后果：

1. **无法断点续跑**：进程崩溃后不知道该从哪一步继续（这正是 Day12 的核心命题）。
2. **无法并发控制**：两个调度线程可能同时解析同一份文档。「进行中」天然是一把幂等锁。
3. **无法观测**：看板上看不出「卡在解析」和「压根没开始」的区别。

### 3.2 DocumentStateMachine：用转移表把非法跳转挡在门外

```java
public static DocumentStatus fire(DocumentStatus from, DocumentStatus to) {
    if (!canFire(from, to)) {
        throw new KnowledgeException(KnowledgeErrorCode.DOCUMENT_STATE_ILLEGAL,
                "文档状态无法从 " + from.description() + " 流转到 " + to.description()
                        + "，允许的下一步: " + allowedTransitions(from));
    }
    return to;
}
```

转移表用 `EnumMap<DocumentStatus, Set<DocumentStatus>>` 而非 `Map<String, Set<String>>`——枚举做 key 不存在拼错字符串导致规则静默失效的可能。

**这里踩到一个真实的坑**，值得单独记一笔：

```java
// ❌ 错误写法：DELETED 是终点，targets 为空数组，会直接抛异常
TRANSITIONS.put(from, EnumSet.copyOf(Arrays.asList(targets)));
// java.lang.IllegalArgumentException: Collection is empty

// ✅ 正确写法
if (targets == null || targets.length == 0) {
    TRANSITIONS.put(from, EnumSet.noneOf(DocumentStatus.class));
    return;
}
```

`EnumSet.copyOf(Collection)` 在**传入空集合且该集合不是 EnumSet** 时会抛 `IllegalArgumentException`。而「终点状态没有任何出边」恰恰是状态机里最常见的情形。这个异常发生在 `<clinit>` 静态初始化阶段，表现为 `ExceptionInInitializerError`——排查起来非常费时。

### 3.3 Document 聚合根：守护三条不变量

```java
public final class Document {
    private final DocumentId id;
    private final TenantId tenantId;      // 不可变
    private final WorkspaceId workspaceId;
    private final KnowledgeBaseId knowledgeBaseId;
    private final byte[] content;         // 保留原文，可重放
    private DocumentVersion version;      // 可变
    private DocumentStatus status;        // 可变
    ...
}
```

**不变量一：租户不可变。** `tenantId` 是 `final`，一经创建永不改变。任何访问都要过：

```java
public void assertBelongsTo(TenantId callerTenantId) {
    if (!this.tenantId.equals(callerTenantId)) {
        throw KnowledgeException.of(KnowledgeErrorCode.TENANT_MISMATCH, ...);
    }
}
```

注意仓储层对跨租户查询的处理是**返回 `Optional.empty()` 而不是抛异常**——「查不到」比「报错暴露了资源存在」更安全，可以避免资源枚举攻击。

**不变量二：状态只走状态机。** 外部拿不到 `setStatus`，只有 `markXxx()`，每个都经过 `transitTo()` → `DocumentStateMachine.fire()`。

**不变量三：原文可追溯。** 保留原始字节且 `content()` 返回副本：

```java
public byte[] content() {
    return content.clone();   // 防止外部通过数组引用篡改聚合内部状态
}
```

### 3.4 DocumentVersion：更新即新版本

```java
public Document nextVersion(byte[] content, String filename) {
    return new Document(this.id, this.tenantId, ..., this.version.next(), this.metadata);
}
```

**为什么不原地改内容？** 因为原地改会让「文本已更新、向量仍是旧的」这个不一致状态成为可能，这正是**向量漂移**的入口。生成新版本 + 状态回到 `UPLOADED` + 重跑整条流水线，语义上才是干净的：要么全是新的，要么全是旧的。

### 3.5 DocumentService：异常处理的第二个坑

```java
} catch (KnowledgeException ex) {
    // 必须先判断 FAILED 是否可达
    if (DocumentStateMachine.canFire(document.status(), DocumentStatus.FAILED)) {
        document.markFailed(ex.getMessage());
        ...
    }
    throw ex;
}
```

**为什么这里有 `if`？** 因为 `INDEXED → FAILED` 本身就不是合法流转。若不加判断直接 `markFailed()`，会抛出**第二个异常**，把真正的失败原因掩盖掉。

这个 Bug 的实际表现很有迷惑性：原本期望看到「无法从 已入库 流转到 已解析」，实际看到的却是「无法从 已入库 流转到 处理失败」——错误信息把人引向完全错误的方向。**「异常处理里再抛异常」是排查线上问题时最耗时的坑之一。**

---

## 四、架构设计思想

1. **聚合根的一致性边界**：Document 的修改必须整体通过聚合根方法，保证「状态 + 版本 + 更新时间」三者永远同步。
2. **端口与适配器**：`DocumentRepository` 是端口，`InMemoryDocumentRepository` 是适配器。内存实现用 `Map<租户ID, Map<文档ID, Document>>` 两级结构，**让租户隔离在数据结构层面就成立**。
3. **事件即解耦**：`DocumentUploadedEvent` 触发解析、`DocumentDeletedEvent` 触发级联清理。未来新增阶段（翻译、去重、打标）只需加监听器，不用改 Service——开闭原则。
4. **返回空而非抛异常**：跨租户查询返回 `empty`，是对外的安全默认。但在聚合根内部（`assertBelongsTo`）则要大声抛异常，因为那是**程序员的错误**，必须立刻暴露。

---

## 五、企业实战案例

### 案例：某券商「幽灵知识」事故复盘

某券商知识库上线半年后，合规抽查发现：员工能检索到**三个月前已被撤回的尽调报告**。

**根因链**：

1. 业务同事在后台「删除」了文档 → 只删了文档表的行记录；
2. 向量库里的 Chunk 没有任何清理动作（当时没有删除事件，也没人想到要删）；
3. 检索照常命中这些 Chunk，且因为文档记录已删，**前端连来源链接都渲染不出来**——用户看到一段无出处的机密内容。

**后果**：一次严重的数据泄漏，合规问责。

**本章的解法**：把「删除」建模成一个**会发出 `DocumentDeletedEvent` 的一等公民动作**，而不是一条 `DELETE` SQL。事件一旦存在，级联清理（Chunk → 向量 → 缓存 → 索引）就有了一个不可绕过的触发点。**这就是事件建模的价值：它让「该做的事」变成「不可能忘记做的事」。**

---

## 六、面试高频题

1. **状态机和一堆 `if (status == X)` 判断相比，本质区别是什么？**
   状态机把「哪些流转合法」变成**数据（转移表）**而非散落的控制流。好处：规则集中可审查、可序列化成配置、可自动生成状态图、非法流转统一报错。分散的 if 则会随代码演进逐渐互相矛盾。

2. **为什么 Entity 可变而 Value Object 不可变？**
   Entity 的同一性由 ID 决定，属性随时间演化是业务事实（订单状态会变）；Value Object 的同一性由值决定，改了值就是另一个对象。混用会导致「共享可变状态」的经典 Bug。

3. **`ThreadLocal` 存租户上下文，在虚拟线程下还成立吗？**
   成立（见第 1 章）。但要注意：**异步线程池里必须显式传递租户上下文**，否则子线程拿到的是 null 或上一个请求的值——这正是第 1 章那个券商串租户事故的根因。

4. **聚合根该不该直接持有文件字节（`byte[]`）？**
   教学与中等规模下可以，好处是流水线可重放。但大文件场景应改为「只在聚合里存存储引用（OSS key）」，字节按需拉取，避免内存压力。本项目为演示完整性保留了字节。

5. **异常处理的 `canFire` 判断是不是过度设计？**
   不是。它防止的是「异常处理逻辑自身抛异常并掩盖原始错误」，这是生产事故排查中代价最高的一类问题。任何时候在 catch 块里做状态变更，都要先确认该变更本身合法。

---

## 七、扩展阅读

- Martin Fowler,《Patterns of Enterprise Application Architecture》—— Unit of Work / Repository。
- 《实现领域驱动设计》Vaughn Vernon —— 聚合根一致性边界。
- Gamma 等,《设计模式》—— State 模式。
- 本项目 Day12 `AgentStateMachine` —— 对比阅读：长任务生命周期状态机，与文档状态机在设计取舍上的异同。

---

## 八、本章小结

- **DocumentStatus** 把流水线的「进行中」也建模为状态，换来可续跑、可并发控制、可观测。
- **DocumentStateMachine** 用 `EnumMap` 转移表集中管理合法流转，并踩到了「空集合初始化 EnumSet」的坑。
- **Document 聚合根**守护三条不变量：租户不可变、状态只走状态机、原文可追溯。
- **DocumentVersion** 让「更新」= 新版本 + 重跑流水线，从源头堵住向量漂移。
- **三个领域事件**（上传 / 状态变更 / 删除）为后续模块解耦与删除同步埋下伏笔。
- **Service 的异常处理**必须先判断 `FAILED` 可达，否则会掩盖真实错误。

### 运行验证

```bash
# 编译后直接运行（零依赖，不需要数据库与 Docker）
java -cp target/classes:<poi/pdfbox 依赖> \
  com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter03Demo
```

真实输出节选：

```
--- 2. 状态机推进（UPLOADED → ... → INDEXED） ---
  → 解析中 → 已解析 → 切分中 → 已切分 → 向量化中 → 已向量化 → 已入库

--- 3. 非法流转被拦截 ---
  ✓ 已拦截: 文档状态无法从 已入库 流转到 已解析，允许的下一步: [PARSING, DELETED]

--- 4. 跨租户访问防护 ---
  rival 未取到文档（返回 empty，不暴露资源存在性）
  同租户可查: true

--- 5. 重新上传 → 新版本 ---
  原版本=v1 → 新版本=v2，状态回到 UPLOADED
  ID 保持不变: true

--- 7. 缺少租户上下文 ---
  ✓ 已拦截: KP-6001 缺少租户上下文
```

> 下一章预告：**第 4 章 · knowledge-parser**，我们将让平台真正吃下 PDF / Word / Excel / PPT / HTML / Markdown，
> 并处理「扫描件没有文本层」这一企业里最常见的麻烦。

——回复「继续」进入第 4 章。
