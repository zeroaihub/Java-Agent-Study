# 第 13 章：knowledge-api —— 统一 API 与门面模式

> 配套代码：`api/`、`application/`
> 可运行 Demo：`demo/Chapter13Demo.java`

---

## 一、这一章要解决的问题

前面 12 章建了 12 个各司其职的模块。**但模块是给"理解"用的**，
调用方需要的不是 12 个模块，而是两个动作：

```
喂进去：Document → 解析 → 切分 → 向量化 → 写双索引
问出来：查询 → 权限定范围 → 混合召回 → 精排 → 去冗余 → 结果
```

本章交付两个东西：

1. **`KnowledgePlatformFacade`（门面）**——把复杂协作封装成两个方法；
2. **REST Controller**——对外暴露 HTTP 接口，供 Office Agent、
   企业微信 Agent、客服机器人、搜索 Agent 统一调用。

> **一套知识，多处消费。** 这正是「平台」与「单点功能」的区别。

---

## 二、核心设计一：门面模式（Facade）

### 2.1 为什么必须收口

如果不加约束，让调用方自己组装 12 个模块，
就会出现「A 接口少做了删除同步」「B 接口忘了权限过滤」这类问题。

**而且由于每个接口看起来都能跑通，这些问题往往会潜伏很久。**

门面模式把复杂的内部协作封装起来，调用方不需要知道内部有 12 个模块，
更不需要知道它们的调用顺序。

### 2.2 门面不依赖 Spring

```java
public class KnowledgePlatformFacade {
    public KnowledgePlatformFacade(DocumentService documentService, ...) { ... }
}
```

**本类刻意没有 `@Service` 注解**：它不依赖 Spring，
构造注入全部依赖，因此可以在没有容器的环境里被 Demo 直接 `new` 出来运行。

由 `api/PlatformConfiguration` 负责把它装配成 Spring Bean。

> **这是"六边形架构"的收益之一**：领域与编排层可以脱离框架独立测试。
> 本项目所有 Demo 都是这么做的——不需要启动 Spring，毫秒级运行。

---

## 三、核心设计二：ingest —— 入库流水线与「先删再写」

```java
public IngestResult ingest(TenantId tenantId, WorkspaceId workspaceId,
                           KnowledgeBaseId kbId, String filename, byte[] content, Metadata metadata) {
    return TenantContext.runWithTenant(tenantId, () -> {
        Document document = documentService.upload(workspaceId, kbId, filename, content, metadata);
        try {
            indexer.removeDocument(tenantId, document.id());   // ← 先清理历史切片
            ParsedDocument parsed = parsingService.parse(document);
            List<Chunk> chunks = chunkingService.chunkAuto(document, parsed);
            documentService.advanceTo(document.id(), DocumentStatus.EMBEDDING);
            List<EmbeddedChunk> embedded = embeddingService.embedAll(chunks);
            documentService.advanceTo(document.id(), DocumentStatus.EMBEDDED);
            indexer.index(embedded);
            documentService.advanceTo(document.id(), DocumentStatus.INDEXED);
            return IngestResult.success(document.id(), chunks.size(), elapsedMs);
        } catch (KnowledgeException ex) {
            return IngestResult.failure(document.id(), "入库失败", ex.getMessage());
        }
    });
}
```

### 3.1 「先删再写」为什么重要

因为文档可能是**重新上传的新版本**：

- 新版本切出 8 片，旧版本是 12 片；
- 如果只做增量覆盖，那**多出来的 4 片旧内容会永远留在索引里**；
- 用户检索时，会同时看到新版本第 3 段和已被删除的旧版本第 9 段。

> **这就是「更新不同步」制造的幽灵知识。**
> 它和删除不同步一样危险，而且**更容易发生**——毕竟更新比删除频繁得多。

### 3.2 reingest：正确表达「更新」语义

`ingest` 每次都会创建一个新的 `Document`（新的 DocumentId）。
用它来做更新，结果是：**新版本的切片进来了，旧版本的切片还留在索引里**，
而且旧切片**永远不会被清理**——因为它们的 DocumentId 与新文档不同，
没有任何机制会把它们关联起来。

所以门面额外提供了 `reingest`：

```java
public IngestResult reingest(TenantId tenantId, DocumentId documentId,
                             String filename, byte[] content) {
    // ① 取出已有文档，生成新版本（ID 不变，版本 +1，状态回到 UPLOADED）
    Document next = existing.nextVersion(content, filename);
    documentRepository.save(next);
    // ② 清除该文档的全部历史切片（新旧版本的切片都在同一批 key 下）
    indexer.removeDocument(tenantId, documentId);
    // ③ 重跑整条流水线
    ...
}
```

Demo 实测：

```
V1（较长）：IngestResult{成功, doc-d3a93cca…, 切片=4}，版本 = v1
V2（大幅缩短）：IngestResult{成功, doc-d3a93cca…, 切片=1}，版本 = v2

索引切片总数：V1 后 12 → V2 后 9
```

**ID 未变、版本号 +1、切片总数净减少 3**——这才是"更新"该有的语义。

---

## 四、核心设计三：search —— 四步顺序不能颠倒

```java
public SearchOutcome search(Principal principal, String queryText,
                            Set<KnowledgeBaseId> scope, int topN) {
    // ① 权限：算出「能读哪些知识库」
    Set<KnowledgeBaseId> readable = aclService.resolveReadableScope(principal, scope);
    if (readable.isEmpty()) {
        return SearchOutcome.empty(queryText, 0, "当前主体无任何可读知识库");
    }
    // ② 召回：只在可读范围内检索
    SearchQuery query = new SearchQuery(principal.tenantId(), queryText, readable,
            Math.max(topN * 4, 20), Metadata.empty());
    RetrievalService.SearchReport report = retrievalService.search(query);
    // ③ 精排 + ④ 去冗余
    List<RetrievedChunk> finalResults = rerankService.rerank(queryText, report.results(), topN);
    return new SearchOutcome(...);
}
```

| 步骤 | 为什么在这个位置 |
| --- | --- |
| **① 权限最前** | 后置过滤会让近似索引的召回率凭空归零，且无权数据已进入内存 |
| **② 召回在中** | 只在"有权看的"范围里检索，过滤条件下推到向量库与倒排索引 |
| **③ 精排在后** | 精排成本高，必须先把候选从百万级收敛到几十条 |
| **④ MMR 最后** | 它需要可信的相关性分数作为输入，所以必须排在精排之后 |

**注意 ① 的返回值是空结果而不是异常**：
「没权限看到任何东西」是一个正常的业务结果，不是系统错误。

---

## 五、核心设计四：REST 接口的三个刻意选择

### 5.1 检索用 POST 而不是 GET

```java
@PostMapping
public KnowledgeResult<SearchResponse> search(@Valid @RequestBody SearchRequest request, ...)
```

- 查询文本可能很长（用户粘贴一整段问题），放在 URL 里会超出长度限制；
- **查询内容属于敏感信息，不应出现在访问日志里**。

### 5.2 不接受 tenantId

```java
public record SearchRequest(String query, Set<String> knowledgeBaseIds, Integer topN) {
    // 刻意没有 tenantId 字段
}
```

租户来自认证上下文，客户端无法指定。**从根源上消除越权。**

这条规则贯穿所有 Controller 与 Service——
见第 9 章「租户只从上下文取」的说明。

### 5.3 权限不足返回 200 + 空结果，而不是 403

「没有权限看到任何东西」是一个**正常的查询结果**。
返回 403 会让调用方把它当成调用错误，从而触发无意义的重试与告警。

### 5.4 topN 必须有上限

```java
public int effectiveTopN() {
    if (topN == null || topN <= 0) return 5;
    return Math.min(topN, 50);
}
```

topN 直接影响精排的计算量与返回体大小。
不设上限的话，`topN=100000` 会瞬间打满 CPU 与带宽。

---

## 六、核心设计五：DTO 与溯源性

```java
public record Hit(String citation, String documentId, int sequence,
                  String title, String text, double score,
                  List<String> matchedBy, Map<String, Integer> ranks) { }
```

**最重要的字段是 `citation`。**

每一条结果都必须能回答「这句话出自哪份文档的哪一段」——
这是企业知识平台区别于聊天机器人的**生死线**：

- **可信**：用户能点开原文核对，而不是盲信 AI 的转述；
- **可审计**：出了问题时能追溯「这次回答基于哪些材料」；
- **可纠偏**：答案错了，用户能立刻定位到是**原文错了**
  还是**模型理解错了**——这两者的修复方式完全不同。

> **没有溯源的 AI 回答，在法律与合规场景下等于没有回答。**

同时，`ranks` 字段保留各路排名用于诊断（见第 7 章的说明）。

---

## 七、核心设计六：租户上下文 Filter（含一条重要警告）

```java
@Override
public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    String tenantValue = resolveTenant(httpRequest);
    try {
        if (tenantValue != null) TenantContext.set(TenantId.of(tenantValue.trim()));
        chain.doFilter(request, response);
    } finally {
        TenantContext.clear();     // ← 必须清理
    }
}
```

### 7.1 为什么 finally 清理是最关键的一步

Web 容器（Tomcat）的线程是**复用**的：
处理完 A 公司的请求后，这个线程会被拿去处理 B 公司的请求。

如果 ThreadLocal 没清理，**B 的请求就会带着 A 的租户上下文运行**——
**B 公司的人看到了 A 公司的数据。**

> 这是教科书级的多租户事故，
> 而且它**只在并发量上来后才偶发，本地测试永远复现不了**。

### 7.2 为什么用 Filter 而不是 Interceptor

Filter 在 Servlet 层面工作，比 Interceptor 更早执行、更晚结束，
能完整覆盖请求的生命周期——包括那些不经过 Spring MVC 的请求
（异步派发、错误派发）。

**租户上下文必须在最早处建立、在最晚处清理，Filter 是唯一正确的位置。**

### 7.3 安全警告：本实现的租户来源不可用于生产

```java
public static final String TENANT_HEADER = "X-Tenant-Id";
```

**从请求头读取租户，仅用于演示与联调，不构成安全实现**——
客户端可以随意伪造这个头，等于租户隔离形同虚设。

> **生产环境必须从认证凭据中推断租户**：
> JWT claims、OAuth2 的租户绑定关系、或 API Key 对应的服务端记录。
> 原则是：**租户只能来自服务端可验证的信息，
> 绝不能来自客户端可以自由指定的字段。**

---

## 八、核心设计七：统一异常处理

```java
@ExceptionHandler(KnowledgeException.class)
public ResponseEntity<KnowledgeResult<Void>> handleKnowledgeException(KnowledgeException ex) {
    log.warn("业务失败 [{}] {}", ex.getErrorCode().code(), ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(KnowledgeResult.fail(ex));
}

@ExceptionHandler(Exception.class)
public ResponseEntity<KnowledgeResult<Void>> handleUnexpected(Exception ex) {
    log.error("系统异常", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(KnowledgeResult.fail("INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
}
```

**最关键的一条：错误码要区分"业务失败"与"系统故障"。**

两者需要的处理完全不同——业务失败是正常流程，记日志即可；
系统故障才需要告警、需要人工介入。

**混为一谈的后果是「告警疲劳」，最终所有告警都无人理会。**

第二个关键点：**不要把内部错误详情暴露给客户端**。
异常信息里可能含 SQL、文件路径、内部服务名，这是信息泄漏。
所以 `handleUnexpected` 只返回固定文案，堆栈只进日志。

---

## 九、核心设计八：装配集中在 PlatformConfiguration

```java
@Configuration
public class PlatformConfiguration {
    @Bean public VectorStore vectorStore() { return new InMemoryVectorStore(); }
    @Bean public Reranker reranker() { return new HeuristicReranker(); }
    // ...
}
```

**前面 13 章写的所有类都没有一个 Spring 注解。**
它们是纯粹的领域代码，可以在没有容器的环境里 `new` 出来直接跑。
装配成 Bean 这件事，**全部集中在这一个文件里完成**。

收益：

1. **领域代码可测试**——不需要启动 Spring 上下文，单测毫秒级运行；
2. **替换实现只改一处**——把 `InMemoryVectorStore` 换成 pgvector，
   只改 `vectorStore()` 一个方法；
3. **依赖方向清晰**——领域不依赖框架，框架依赖领域。

---

## 十、运行 Demo

```bash
java -cp target/classes:<依赖> com.zero.ai.agentstudy.day15knowledgeplatform.demo.Chapter13Demo
```

真实输出（节选）：

```
[1] 入库
  差旅报销制度.md   → IngestResult{成功, doc-40cdacab…, 切片=4, 75ms}
  发票要求.md      → IngestResult{成功, doc-dc769971…, 切片=1, 10ms}
  索引状态：向量库 9 条，倒排索引 9 条，反查表 9 条

[3] 机密知识库的隔离验证
  【alice（组：[finance]）】薪酬调整幅度是多少
    - doc-40cdacab…#0 [0.8388] # 差旅报销制度      ← 全是无关的制度内容
  【eric（组：[core]）】薪酬调整幅度是多少
    - doc-e78d6fca…#2 [1.3967] ## 调整幅度 平均上调 18%…   ← 精准命中

[4] 删除同步
  DeleteReport{vector=1, bm25=1, registry=1, consistent=true}
  ✓ 一致

[5] 更新不同步
  V1：切片=4，版本 = v1
  V2：切片=1，版本 = v2
  索引切片总数：V1 后 12 → V2 后 9
```

**第 3 节的关键观察**：

eric（core 组，可读 2 个库）精准命中「平均上调 18%」；
alice（finance 组，只读 1 个库）返回的全是差旅制度里的内容——
**它们与「薪酬」毫无关系，是检索在她有权的范围内尽力匹配的结果**。

这不是"检索失败"，而是"**权限收敛**"：
机密库从一开始就不在她的可读集合里，那段内容从未被读取，连内存都没进过。

---

## 十一、接口清单

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/knowledge/search` | SEARCH | 权限感知混合检索 |
| POST | `/api/knowledge/documents` | WRITE | 上传文档并入库 |
| GET | `/api/knowledge/documents` | SEARCH | 列出知识库内文档 |
| DELETE | `/api/knowledge/documents/{id}` | DELETE | 删除文档（含索引级联清理） |
| POST | `/api/knowledge/workspaces` | — | 创建工作空间 |
| GET | `/api/knowledge/workspaces` | — | 列出工作空间 |
| POST | `/api/knowledge/workspaces/{id}/knowledge-bases` | — | 创建知识库 |
| GET | `/api/knowledge/workspaces/{id}/knowledge-bases` | — | 列出知识库 |
| POST | `/api/knowledge/admin/acl/grant` | MANAGE | 授权（留痕） |
| GET | `/api/knowledge/admin/acl` | MANAGE | 查看 ACL |
| GET | `/api/knowledge/admin/stats` | — | 平台统计与一致性检查 |

演示用请求头：`X-Tenant-Id`、`X-User-Id`、`X-User-Groups`（生产请改为认证信息）。

---

## 十二、避坑指南

| 坑 | 后果 | 对策 |
| --- | --- | --- |
| 调用方自己组装模块 | A 接口漏了删除同步、B 接口漏了权限 | 门面收口 |
| 用 ingest 做更新 | 旧切片永远残留 | 提供 `reingest`（同 ID、版本 +1、先删再写） |
| 检索用 GET | URL 超长 + 查询内容进访问日志 | 用 POST |
| 接口接受 tenantId | 越权漏洞 | 租户只从上下文取 |
| 权限不足返回 403 | 被当成调用错误，触发无意义重试 | 返回 200 + 空结果 |
| topN 无上限 | 打满 CPU 与带宽 | 限制上限（50） |
| Filter 中不清理 ThreadLocal | 线程复用导致租户串号 | `finally { TenantContext.clear(); }` |
| 租户来自请求头 | 可伪造，隔离形同虚设 | 生产改用 JWT/OAuth2 |
| 异常详情直接返回客户端 | 信息泄漏（SQL、路径、服务名） | 只返回固定文案，堆栈进日志 |
| 业务失败与系统故障混为一谈 | 告警疲劳，真故障被淹没 | 分开错误码与日志级别 |

---

## 十三、面试题

**Q1：为什么需要门面（Facade）？直接调用各模块不行吗？**

> 不收口的话，「模块调用顺序」这份知识会散落到各个 Controller 里，
> 于是出现「A 接口少做了删除同步」「B 接口忘了权限过滤」这类问题。
>
> **而且由于每个接口看起来都能跑通，这些问题往往会潜伏很久**，
> 直到某天用户投诉"搜到了已删除的内容"才暴露。
>
> 门面把顺序固化在一处，调用方只需要知道两个方法：
> `ingest()` 和 `search()`。新增调用方时不可能出错。

**Q2：入库流水线为什么要「先删再写」？**

> 因为文档可能是重新上传的新版本：
> 新版本切出 8 片，旧版本是 12 片，
> 只做增量覆盖的话，**多出来的 4 片旧内容会永远留在索引里**，
> 用户检索时会同时看到新旧两版内容。
>
> 这就是「更新不同步」制造的幽灵知识。
> 它和删除不同步一样危险，而且**更容易发生**——毕竟更新比删除频繁得多。
>
> 配套要求：更新必须是**同一个 DocumentId、版本号 +1**
> （即 `reingest`），否则新旧切片根本无法关联，删除也就无从谈起。

**Q3：为什么 Filter 里必须用 finally 清理 ThreadLocal？**

> 因为 Web 容器的线程是**复用**的：
> 处理完 A 公司的请求后，这个线程会被拿去处理 B 公司的请求。
>
> 如果 ThreadLocal 没清理，**B 的请求就会带着 A 的租户上下文运行**——
> **B 公司的人看到了 A 公司的数据。**
>
> 这是教科书级的多租户事故，而且它**只在并发量上来后才偶发，
> 本地测试永远复现不了**，所以必须在代码层面强制保证。

**Q4：为什么业务失败和系统故障要分开处理？**

> 因为两者需要的响应完全不同：
> - **业务失败**（参数非法、无权访问）是正常流程，返回 400，记 warn 日志即可；
> - **系统故障**（NPE、DB 挂了）才需要 500、ERROR 日志、告警、人工介入。
>
> 混为一谈的后果是**告警疲劳**——
> 监控系统里全是"用户传错参数"的噪音，
> 结果真正的数据库连接失败被淹没其中，无人理会。
>
> 还有一条安全要求：**系统异常不要向客户端暴露详情**，
> 异常信息里可能含 SQL、文件路径、内部服务名。

---

## 十四、本章小结

- **门面把 12 个模块收口成两个方法**，让新增调用方时不可能出错。
- **入库必须「先删再写」**，更新要走 `reingest`（同 ID、版本 +1）。
- **检索四步顺序**：权限 → 召回 → 精排 → MMR，一步都不能颠倒。
- **REST 接口的三个选择**：POST、不接受 tenantId、无权返回 200+空结果。
- **citation 是企业知识平台的生死线**——没有溯源等于没有回答。
- **Filter 的 finally 清理 ThreadLocal** 是多租户的必备防护。
- **装配集中在 PlatformConfiguration**，替换实现只改一处。

下一章是最后一章：管理端、授权审计、平台统计，
以及一份诚实的「教学实现 vs 生产实现」差距清单。

---

> **第 13 章结束。** 输入「继续」，进入 **第 14 章：knowledge-admin —— 管理端与全链路复盘**。
