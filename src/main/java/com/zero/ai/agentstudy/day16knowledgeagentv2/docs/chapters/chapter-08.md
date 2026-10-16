# Chapter 08 · ACL 权限与知识隔离

> 检索链的最后一道「安检门」。前面的 Router / Query / Retrieval / Rerank 只关心「哪些内容最相关」，本章只关心一件事：**这些相关内容，当前用户到底有没有资格看到。**

---

## 一、为什么学（Why）

### 1.1 一个真实的翻车现场

某公司把内部知识库接入了企业 Agent。销售小王问：「我们和 A 客户的最终成交价是多少？」——Agent 检索到了那份只对「商务负责人」开放的合同附件，字正腔圆地把折扣底价念了出来。

问题出在哪？**检索系统只对「相关性」负责，不对「可见性」负责。** 向量库不会因为小王没权限就把那份合同的余弦相似度调低——它照样排在第一。

### 1.2 库级前置权限（Ch03）不够用了

Ch03 的 Knowledge Routing 做的是**库级**前置过滤：小王能访问「销售库」，就把整个销售库放进候选。但企业真实场景是：

- **同库不同人可见范围不同**：销售库里，A 客户的资料只有 A 客户的负责人能看。
- **同一文档不同密级**：一篇「产品手册」，公开章节人人可看，未发布章节只有产品团队可看。
- **动态组织关系**：小王今天调去 B 团队，昨天能看的今天就不能看了。

这些都不是「库级开关」能表达的，需要**行级（Row-Level）/ 文档级（Document-Level）** 的精细权限控制。

### 1.3 本章要解决的三个命题

1. **在哪一步过滤**：权限下推到检索（DB 层过滤）vs 先检索后过滤（应用层过滤）？
2. **过滤依据什么**：文档带什么权限标签，用户带什么权限主体，如何比对？
3. **过滤后不够了怎么办**：本来 Top5，过滤掉 3 条只剩 2 条，要不要回填？宁缺毋滥还是补齐？

---

## 二、是什么（What）

### 2.1 两种过滤时机对比

```
用户查询
   │
   ├─ 方案 A：权限下推（Filter Pushdown）
   │     检索时就带上 WHERE acl_tags && :user_tags
   │     ——数据库/向量库只返回有权限的结果
   │
   └─ 方案 B：先检索后过滤（Post-Filter）
         检索返回 TopN → 应用层逐条比对权限 → 剔除无权限
```

| 维度 | 权限下推（Pushdown） | 先检索后过滤（Post-Filter） |
| --- | --- | --- |
| 安全性 | 高（无权限数据不出库） | 中（数据已出库，靠应用层拦） |
| 性能 | 高（少传数据） | 差（可能检索 100 条过滤剩 5 条） |
| 结果数量 | 稳定（TopN 都是有权限的） | 会「缩水」（过滤后可能不足 N） |
| 实现复杂度 | 高（要改检索层 SQL / filter DSL） | 低（应用层加一层 filter） |
| 适用 | 生产主力方案 | 教学演示 / 检索层不支持下推时兜底 |

> **黄金实践**：**能下推就下推**（安全 + 性能双赢），下推 + 应用层二次校验做纵深防御。本章为聚焦「权限判定逻辑」，用 **Post-Filter** 演示（逻辑等价，且不依赖具体向量库 DSL），生产环境请优先下推。

### 2.2 权限模型：文档带标签，用户带主体

最通用、可落地的模型是 **ACL 标签匹配**：

```
文档侧（写入时打标）           用户侧（AgentContext 携带）
┌─────────────────┐          ┌─────────────────┐
│ aclTags:        │          │ roles:          │
│  ["team:sales", │  ── ∩ ──▶│  ["team:sales", │
│   "level:conf"] │  有交集   │   "level:public"│
└─────────────────┘  即可见   └─────────────────┘
```

判定规则（可按业务演化）：

- **管理员直通**：`isAdmin()` → 可见全部（谨慎，需审计）。
- **公开文档**：`aclTags` 为空 → 视为公开，人人可见。
- **受限文档**：用户 `roles` 与文档 `aclTags` **有交集**即可见（OR 语义）；也可要求「全部命中」（AND 语义，更严格）。

### 2.3 在检索链中的位置

```
Router → Query → Retrieval → Rerank → 【ACL 过滤】→ Citation → Verifier
                                          ↑
                          本章：重排后、生成前的「安检门」
```

放在**重排之后**：先让最相关的浮上来，再安检——避免「先安检把大量无关内容也一并处理」的浪费；也保证送进 LLM 的每一条都是**既相关又有权限**的。

---

## 三、怎么用（How）

> 所有代码位于包 `com.zero.ai.agentstudy.day16knowledgeagentv2.acl`，复用 Ch05 的 `RetrievedChunk` 与 core 的 `AgentContext`，可独立编译运行。

### 3.1 为 RetrievedChunk 附加权限标签的载体

`RetrievedChunk` 本身不含权限信息（它是纯检索结果）。我们用一个**装饰载体** `AclChunk` 把「检索结果」与「它的权限标签」绑在一起，避免污染 Ch05 的 record。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.acl;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval.RetrievedChunk;

import java.util.List;
import java.util.Set;

/**
 * 带权限标签的检索结果（装饰载体）。
 *
 * <p>{@link RetrievedChunk} 只负责「内容 + 相关性」，本类为其附加「谁能看」的
 * ACL 标签。二者分离，检索层无需感知权限模型，权限层无需感知检索细节。</p>
 *
 * @param chunk   底层检索结果
 * @param aclTags 该文档的访问控制标签；空集合视为「公开」
 * @author ZeroAi
 */
public record AclChunk(RetrievedChunk chunk, Set<String> aclTags) {

    public AclChunk {
        AgentException.requireNonNull(chunk, "chunk");
        // 标签可为空（公开文档），但不允许 null
        aclTags = aclTags == null ? Set.of() : Set.copyOf(aclTags);
    }

    /**
     * 便捷构造：无标签 = 公开文档。
     */
    public static AclChunk publicChunk(RetrievedChunk chunk) {
        return new AclChunk(chunk, Set.of());
    }

    /**
     * 便捷构造：受限文档。
     */
    public static AclChunk restricted(RetrievedChunk chunk, String... tags) {
        return new AclChunk(chunk, Set.of(tags));
    }

    public boolean isPublic() {
        return aclTags.isEmpty();
    }
}
```

### 3.2 定义权限判定端口 AccessController

把「判定逻辑」抽象为端口，未来可替换为「查数据库 / 调权限中心 / 走 OPA 策略引擎」等不同实现。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.acl;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.List;

/**
 * 访问控制器（端口）。检索链中「安检门」的抽象。
 *
 * <p>只做一件事：给定请求上下文与候选结果，返回「当前用户有权看到」的子集。
 * 具体判定策略（标签匹配 / RLS / 策略引擎）由适配器实现。</p>
 *
 * @author ZeroAi
 */
public interface AccessController {

    /**
     * 判断单条是否对当前用户可见。
     */
    boolean canAccess(AgentContext context, AclChunk chunk);

    /**
     * 过滤出当前用户有权访问的结果，保持原有顺序（相关性次序不被打乱）。
     */
    List<AclChunk> filter(AgentContext context, List<AclChunk> chunks);
}
```

### 3.3 标签匹配实现 TagBasedAccessController

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.acl;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.List;

/**
 * 基于 ACL 标签的访问控制器。
 *
 * <p>判定规则（OR 语义）：</p>
 * <ol>
 *     <li>管理员（{@code isAdmin()}）直通——可见全部；</li>
 *     <li>公开文档（无标签）——人人可见；</li>
 *     <li>受限文档——用户角色与文档标签有交集即可见。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public final class TagBasedAccessController implements AccessController {

    @Override
    public boolean canAccess(AgentContext context, AclChunk chunk) {
        AgentException.requireNonNull(context, "context");
        AgentException.requireNonNull(chunk, "chunk");

        // 规则 1：管理员直通（生产中应记审计日志）
        if (context.isAdmin()) {
            return true;
        }
        // 规则 2：公开文档人人可见
        if (chunk.isPublic()) {
            return true;
        }
        // 规则 3：角色与文档标签有交集即可见（OR 语义）
        for (String tag : chunk.aclTags()) {
            if (context.hasRole(tag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<AclChunk> filter(AgentContext context, List<AclChunk> chunks) {
        AgentException.requireNonNull(chunks, "chunks");
        return chunks.stream()
                .filter(chunk -> canAccess(context, chunk))
                .toList();
    }
}
```

### 3.4 运行演示 Chapter08Demo

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.acl;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval.RetrievedChunk;

import java.util.List;
import java.util.Set;

/**
 * Chapter 08 演示：同一批检索结果，不同用户看到不同子集。
 *
 * @author ZeroAi
 */
public final class Chapter08Demo {

    public static void main(String[] args) {
        KnowledgeBaseId sales = KnowledgeBaseId.of("kb-sales");

        // 重排后送来的 TopN（相关性已排好序）
        List<AclChunk> ranked = List.of(
                AclChunk.publicChunk(new RetrievedChunk("c1", sales, "产品公开手册第一章", 0.95)),
                AclChunk.restricted(new RetrievedChunk("c2", sales, "A 客户成交底价合同", 0.92), "customer:A"),
                AclChunk.restricted(new RetrievedChunk("c3", sales, "销售团队内部话术", 0.88), "team:sales"),
                AclChunk.restricted(new RetrievedChunk("c4", sales, "财务未发布报表", 0.85), "team:finance")
        );

        AccessController acl = new TagBasedAccessController();

        // 用户甲：销售团队成员
        AgentContext sale = AgentContext.of(
                TenantId.of("t1"), WorkspaceId.of("w1"), UserId.of("u-sale"),
                Set.of("team:sales"));
        printResult("销售小王(team:sales)", acl.filter(sale, ranked));

        // 用户乙：A 客户负责人
        AgentContext kam = AgentContext.of(
                TenantId.of("t1"), WorkspaceId.of("w1"), UserId.of("u-kam"),
                Set.of("customer:A"));
        printResult("A客户负责人(customer:A)", acl.filter(kam, ranked));

        // 用户丙：管理员
        AgentContext admin = AgentContext.of(
                TenantId.of("t1"), WorkspaceId.of("w1"), UserId.of("u-admin"),
                Set.of("ADMIN"));
        printResult("管理员(ADMIN)", acl.filter(admin, ranked));
    }

    private static void printResult(String who, List<AclChunk> visible) {
        System.out.println("== " + who + " 可见 " + visible.size() + " 条 ==");
        for (AclChunk c : visible) {
            System.out.println("  - " + c.chunk().chunkId() + " : " + c.chunk().content());
        }
    }
}
```

**运行输出示意**：

```
== 销售小王(team:sales) 可见 2 条 ==
  - c1 : 产品公开手册第一章
  - c3 : 销售团队内部话术
== A客户负责人(customer:A) 可见 2 条 ==
  - c1 : 产品公开手册第一章
  - c2 : A 客户成交底价合同
== 管理员(ADMIN) 可见 4 条 ==
  - c1 : 产品公开手册第一章
  - c2 : A 客户成交底价合同
  - c3 : 销售团队内部话术
  - c4 : 财务未发布报表
```

同一批相关内容，销售小王看不到 A 客户底价（c2）、看不到财务报表（c4）——**安检门生效**，且相关性次序原样保留。

---

## 四、真实项目中的落地

1. **优先权限下推（Filter Pushdown）**：pgvector 用 `WHERE acl_tags && ARRAY[:userTags]`（数组重叠操作符 `&&`），Elasticsearch 用 `terms` filter 塞进 bool query。无权限数据**根本不出库**，安全 + 快。
2. **下推 + 应用层二次校验（纵深防御）**：下推负责性能，应用层 `AccessController.filter` 负责兜底——防止 filter DSL 写错导致越权。安全无小事，多一层不亏。
3. **权限主体从统一鉴权中心解析**：`AgentContext.roles()` 生产中来自 JWT / OAuth2 Scope / 组织架构服务，而非硬编码；调岗即失效，靠 token 有效期 + 实时校验。
4. **AND / OR 语义按密级分层**：一般文档用 OR（任一命中即可见），高密文档要求 AND（必须同时具备 `team:finance` 且 `level:confidential`）——把 `canAccess` 抽成可配置策略。
5. **审计日志**：管理员直通、越权尝试（检索到但被过滤掉）都要落审计，供合规追溯。`traceId` 串联全链路。

---

## 五、避坑与优化

### 5.1 五个高频坑

| 坑 | 后果 | 正确做法 |
| --- | --- | --- |
| 只做库级权限（Ch03 就完事） | 同库越权，底价泄露 | 叠加行级/文档级 ACL 过滤 |
| 只在应用层过滤、不下推 | 无权限数据已出库，且检索 100 条剩 5 条，慢 | 优先下推，应用层做二次校验 |
| 「无标签=禁止访问」误设 | 大量公开文档谁都看不到 | 明确约定「无标签=公开」，受限文档必须显式打标 |
| 管理员直通不审计 | 权限滥用无迹可查 | isAdmin 直通必须落审计日志 |
| 过滤后数量缩水直接送 LLM | 上下文过少，答案质量下降 | 见 5.2 回填策略 |

### 5.2 过滤后「缩水」怎么办

重排给了 Top5，ACL 过滤掉 3 条只剩 2 条——两种策略：

- **宁缺毋滥（推荐默认）**：就用这 2 条，不足则触发 **Knowledge Gap**（Ch11）——「基于你的权限范围，暂无足够信息」，绝不用无权限内容凑数。
- **权限内回填**：向检索层要「权限内的下一批」补齐到 N 条。需要**权限下推**支持（否则又是检索一大批再过滤的老问题）。

**核心原则：安全永远优先于完整性。** 宁可答「我不知道」，不可越权作答。

### 5.3 性能优化

- **标签集合用 Set 做交集**：`O(min(a,b))`，别用 List 双重循环。
- **热点用户权限缓存**：`userId → roles` 短 TTL 缓存（如 Redis 60s），避免每次检索都查组织架构。
- **下推优先**：从根上减少数据传输与应用层计算量。

---

## 小结

- **相关 ≠ 有权限**：检索只管相关性，ACL 才管可见性，二者必须解耦（`RetrievedChunk` 纯净、`AclChunk` 装饰）。
- **能下推就下推**：安全 + 性能双赢；下推 + 应用层二次校验做纵深防御。
- **标签匹配是最通用模型**：文档打 `aclTags`、用户带 `roles`，有交集即可见；高密文档改 AND 语义。
- **过滤后不足宁缺毋滥**：转 Knowledge Gap，绝不越权凑数——安全优先于完整性。

> **下一章预告 · Chapter 09 · Citation 引用溯源**：现在送进 LLM 的每一条都既相关又有权限了，但用户凭什么信 Agent 说的？下一章我们让每一句答案都能「点回原文」——给答案装上可追溯的引用脚注。