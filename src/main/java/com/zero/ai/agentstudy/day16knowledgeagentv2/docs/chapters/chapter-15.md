# Chapter 15 · Multi-Tenant 多租户隔离

> 企业知识库 Agent V2 · 第 15 章
> 前 14 章,`tenantId` 一直作为一个"字段"贯穿 `AgentContext` 与可观测性。本章把它从"一个字段"升级为**系统级的隔离边界**——这是把单机知识库 Agent 变成能服务成百上千家企业的 **SaaS 平台**的地基。

---

## 1. 为什么学(Why)

### 1.1 一次数据串租户,足以让公司关门

设想:客户 A(某银行)登录知识库 Agent,提问"我们的风控内规",却被检索到了客户 B(竞争对手)的机密文档。这不是 bug,这是**事故**——在企业 SaaS 里,**数据串租户(cross-tenant data leak)是最高优先级的红线**,一次就可能导致合同终止、法律诉讼、公司信誉崩塌。

知识库 Agent 尤其危险,因为它天然是"检索 + 生成":

- **检索**可能跨租户捞到别家的向量;
- **生成**可能把别家的内容"编"进答案;
- **缓存/日志**可能把租户 A 的数据泄漏给租户 B。

所以多租户隔离不是"锦上添花",而是**架构第一原则**:宁可少一个功能,不可漏一次数据。

### 1.2 隔离的三个层次

"多租户"不是一个开关,而是从物理到逻辑的一道谱系:

| 模式 | 隔离强度 | 成本 | 适用 |
|------|----------|------|------|
| **独立部署(Silo)** | 最强(物理隔离,一租户一套) | 最高 | 强合规大客户(银行、政府) |
| **独立库/Schema(Bridge)** | 中(共享应用,独立数据) | 中 | 中大型客户 |
| **共享库行级(Pool)** | 最弱(全共享,靠 tenant 列) | 最低 | 海量中小客户 |

企业级 SaaS 通常是**混合模式**:大客户走 Silo,长尾客户走 Pool。本章聚焦最普适、也最容易出事的 **Pool 行级隔离**——因为它把隔离的责任完全交给了**代码正确性**。

### 1.3 Pool 模式的核心矛盾:方便 vs 安全

行级隔离的做法是:所有租户的数据存在同一张表,靠一个 `tenant_id` 列区分。查询时**必须**带 `WHERE tenant_id = ?`。

矛盾就在这个"必须":
- 只要有**一个**查询忘了带 tenant 过滤 → 串租户;
- 只要有**一个**缓存 key 忘了带 tenant 前缀 → 串租户;
- 只要有**一次**向量检索没按 tenant 分区 → 串租户。

人是会犯错的。**企业级方案的精髓,是让"忘记带 tenant"这件事在架构层面变得不可能**,而不是靠开发者自觉。这正是本章的核心。

---

## 2. 是什么(What)

### 2.1 强制在场:TenantContext 必须存在

第一道防线:任何进入领域层的操作,都**必须**携带一个已验证的租户身份。我们已经有了 `AgentContext`(内含 `TenantId`),本章强化它:**没有合法 TenantId,请求根本进不了推理链**(fail-fast)。

### 2.2 默认安全:隔离靠"框架"而非"自觉"

第二道防线:把 tenant 过滤做成**默认行为**,而非每次手写。三种典型手段:

- **数据层**:Repository 基类统一注入 `tenant_id` 过滤(或用 PostgreSQL 行级安全 RLS);
- **向量层**:每次向量检索强制带 `metadata.tenant_id` 过滤(或每租户独立 collection);
- **缓存层**:所有 key 统一加 `tenant:{id}:` 前缀,由封装层自动拼接。

### 2.3 纵深防御:ACL 只是最后一道,不是唯一一道

Ch07 我们做了 ACL(访问控制)。但 ACL 是**文档级/用户级**的权限,它**不能替代租户级隔离**:

```
租户隔离(tenant)  →  最外层:你连别家的表/collection 都碰不到
   ACL(user/role) →  次外层:同租户内,你只能看有权限的文档
```

**顺序至关重要**:先隔离租户,再做 ACL。如果只做 ACL,一旦 ACL 有 bug,就直接串租户。多层防御(Defense in Depth)——每一层都假设上一层可能失守。

### 2.4 本章交付物

一个演示"默认安全"隔离的骨架:

- `TenantId`(已存在,Ch02):强类型租户标识,复用。
- `TenantScopedQuery`:携带 tenant 的查询上下文,构造时强制校验。
- `TenantAwareRepository<T>`:抽象基类,所有查询**自动**注入 tenant 过滤,子类无法绕过。
- `KnowledgeChunkRepository`:示例实现,存储所有租户的片段。
- `TenantCacheKey`:强制带 tenant 前缀的缓存 key 工厂。
- `CrossTenantGuard`:运行时哨兵,校验取出的数据 tenant 与请求 tenant 一致(纵深防御的"保险丝")。
- `Chapter15Demo`:演示"租户 A 查不到租户 B 的数据",以及哨兵拦截越权。

---

## 3. 怎么用(How)

> 包路径:`com.zero.ai.agentstudy.day16knowledgeagentv2.tenant`
> 复用 `core.model.TenantId`(Ch02),零框架依赖。

### 3.1 TenantScopedQuery:构造即校验的查询上下文

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.tenant;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.Objects;

/**
 * 租户作用域查询:任何数据访问都必须先构造它。
 * 构造时强制校验 TenantId 在场,从源头杜绝"无 tenant 查询"。
 *
 * @param tenantId 已验证的租户标识,不可为 null
 * @param keyword  业务查询条件(演示用简单关键字)
 */
public record TenantScopedQuery(TenantId tenantId, String keyword) {

    public TenantScopedQuery {
        Objects.requireNonNull(tenantId, "TenantId 必须在场——禁止无租户查询");
        keyword = keyword == null ? "" : keyword.trim();
    }

    /** 从 tenant + 关键字构造。 */
    public static TenantScopedQuery of(TenantId tenantId, String keyword) {
        return new TenantScopedQuery(tenantId, keyword);
    }
}
```

### 3.2 TenantAwareRepository:子类无法绕过的隔离基类

这是"默认安全"的核心——**tenant 过滤在基类里强制执行,子类只提供业务逻辑**:

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.tenant;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.List;

/**
 * 租户感知仓储基类。
 * 关键设计:公开的 find 方法是 final 的,它先强制取出 tenant 过滤,
 * 再调用子类的 doFind——子类拿到的数据【已经】是本租户的,无从越权。
 *
 * @param <T> 领域对象类型
 */
public abstract class TenantAwareRepository<T> {

    /**
     * 唯一对外入口:强制走 tenant 过滤。final 禁止子类覆盖。
     */
    public final List<T> find(TenantScopedQuery query) {
        TenantId tenant = query.tenantId(); // 构造时已保证非空
        // 隔离动作在此统一发生:子类拿到的候选集必须已被 tenant 限定
        return doFind(tenant, query.keyword());
    }

    /**
     * 子类只实现"在【指定租户】范围内如何查",拿不到跨租户数据。
     * 生产环境:此处对应 SQL 的 WHERE tenant_id = ? 或向量库 metadata 过滤。
     */
    protected abstract List<T> doFind(TenantId tenant, String keyword);
}
```

### 3.3 KnowledgeChunkRepository:示例实现

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.tenant;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 演示:所有租户的片段存在同一个 Map(模拟共享表 Pool 模式)。
 * doFind 里【必须】先按 tenant 过滤——这正是行级隔离在代码里的落点。
 */
public final class KnowledgeChunkRepository extends TenantAwareRepository<String> {

    /** tenantId.value() -> 该租户的片段列表(模拟一张共享表)。 */
    private final Map<String, List<String>> table = new ConcurrentHashMap<>();

    public void seed(TenantId tenant, List<String> chunks) {
        table.put(tenant.value(), List.copyOf(chunks));
    }

    @Override
    protected List<String> doFind(TenantId tenant, String keyword) {
        // 第一步永远是 tenant 过滤:只取本租户分区
        List<String> scoped = table.getOrDefault(tenant.value(), List.of());
        // 第二步才是业务匹配
        return scoped.stream()
                .filter(c -> keyword.isEmpty() || c.contains(keyword))
                .collect(Collectors.toList());
    }
}
```

### 3.4 TenantCacheKey:强制带前缀的缓存 key

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.tenant;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.Objects;

/** 缓存 key 工厂:任何 key 都必须经它拼上 tenant 前缀,杜绝跨租户缓存命中。 */
public final class TenantCacheKey {

    private TenantCacheKey() {}

    /** 生成形如 "tenant:{id}:{namespace}:{key}" 的隔离 key。 */
    public static String of(TenantId tenant, String namespace, String key) {
        Objects.requireNonNull(tenant, "TenantId 不能为 null");
        return "tenant:" + tenant.value() + ":" + namespace + ":" + key;
    }
}
```

### 3.5 CrossTenantGuard:纵深防御的保险丝

即便前面都做对了,再加一道**运行时断言**——万一有数据的 tenant 与请求 tenant 不符,立即抛异常而非返回:

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.tenant;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.function.Function;

/**
 * 跨租户哨兵:校验取出的每条数据的归属 tenant 是否等于请求 tenant。
 * 这是"信任但验证"——即使上游隔离失效,也在返回前熔断。
 */
public final class CrossTenantGuard {

    private CrossTenantGuard() {}

    /**
     * @param requestTenant 请求方租户
     * @param dataTenantOf  从数据对象提取其归属 tenant 的函数
     * @param data          待校验的数据对象
     * @throws SecurityException 若归属 tenant 与请求 tenant 不一致
     */
    public static <T> T assertSameTenant(
            TenantId requestTenant, Function<T, TenantId> dataTenantOf, T data) {
        TenantId owner = dataTenantOf.apply(data);
        if (!requestTenant.equals(owner)) {
            throw new SecurityException(
                    "跨租户访问拦截:请求=" + requestTenant.value()
                            + " 数据归属=" + (owner == null ? "null" : owner.value()));
        }
        return data;
    }
}
```

### 3.6 Chapter15Demo:租户隔离演示

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.tenant;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.List;

/** 演示:A 查不到 B 的数据 + 缓存 key 隔离 + 哨兵拦截越权。 */
public final class Chapter15Demo {

    public static void main(String[] args) {
        TenantId acme = TenantId.of("tenant-acme");
        TenantId globex = TenantId.of("tenant-globex");

        KnowledgeChunkRepository repo = new KnowledgeChunkRepository();
        repo.seed(acme, List.of("Acme 风控内规 v3", "Acme 报销制度"));
        repo.seed(globex, List.of("Globex 机密并购计划"));

        // 场景一:Acme 查"内规",只命中 Acme 自己的数据
        List<String> r1 = repo.find(TenantScopedQuery.of(acme, "内规"));
        System.out.println("Acme 查[内规] => " + r1);

        // 场景二:Acme 试图搜"并购"(那是 Globex 的),结果为空——碰不到别家
        List<String> r2 = repo.find(TenantScopedQuery.of(acme, "并购"));
        System.out.println("Acme 查[并购] => " + r2 + "  (跨租户不可见)");

        // 场景三:缓存 key 隔离
        System.out.println("Acme 缓存 key   => "
                + TenantCacheKey.of(acme, "answer", "q123"));
        System.out.println("Globex 缓存 key => "
                + TenantCacheKey.of(globex, "answer", "q123"));

        // 场景四:哨兵拦截——假设某条数据实际归属 globex,却在 acme 请求里出现
        try {
            record Chunk(TenantId owner, String text) {}
            Chunk leaked = new Chunk(globex, "Globex 机密");
            CrossTenantGuard.assertSameTenant(acme, Chunk::owner, leaked);
        } catch (SecurityException e) {
            System.out.println("哨兵拦截 => " + e.getMessage());
        }
    }
}
```

**运行输出示意**:

```
Acme 查[内规] => [Acme 风控内规 v3]
Acme 查[并购] => []  (跨租户不可见)
Acme 缓存 key   => tenant:tenant-acme:answer:q123
Globex 缓存 key => tenant:tenant-globex:answer:q123
哨兵拦截 => 跨租户访问拦截:请求=tenant-acme 数据归属=tenant-globex
```

---

## 4. 真实项目落地

### 4.1 数据库层:三种落地方式

1. **应用层过滤(最灵活)**:所有 SQL 强制拼 `WHERE tenant_id = ?`。用 MyBatis 拦截器 / JPA `@Filter` / Hibernate `@TenantId` 自动注入,避免手写遗漏。
2. **PostgreSQL 行级安全(RLS,最可靠)**:在数据库层设 `CREATE POLICY`,连接时 `SET app.current_tenant = ?`,数据库**强制**只返回本租户行。即使应用层忘了过滤,数据库也兜底。这是 Pool 模式的黄金实践。
3. **独立 Schema(Bridge)**:每租户一个 schema,连接时切换 `search_path`。隔离更强,但 schema 数量有上限,适合中等规模租户数。

### 4.2 向量库层:pgvector / Elasticsearch 隔离

- **pgvector**:向量表同样带 `tenant_id` 列 + RLS,检索 SQL 带 tenant 过滤。
- **Elasticsearch/OpenSearch**:用 **routing + filter**,或大客户独立 index。检索 query 的 `filter` 子句强制含 `term: {tenant_id: ...}`。
- **向量检索的隐患**:ANN 近似检索若不先过滤 tenant,可能召回别家向量再过滤,既慢又危险。正确做法是**过滤下推**(pre-filter),让隔离发生在检索内部。

### 4.3 让隔离"默认发生"的工程手段

- **AOP 切面**:在 Repository 方法执行前,从 `AgentContext` 取 tenant 注入 ThreadLocal / RLS 会话变量。
- **架构测试**:用 ArchUnit 写规则——"任何 Repository 的公开查询方法参数必须含 TenantScopedQuery",在 CI 里静态拦截违规代码。
- **契约测试**:每个 Repository 都有一个"租户 A 查不到租户 B 数据"的强制测试用例,作为回归防线。

### 4.4 别忘了非数据面的隔离

串租户不只发生在数据库:

- **缓存**:Redis key 必须带 tenant 前缀(本章 `TenantCacheKey`)。
- **消息队列**:消息体带 tenantId,消费端校验。
- **限流/配额**:按 tenant 维度限流,防止一个租户打爆共享资源(见 Ch16 计量)。
- **日志/可观测性**:Ch14 已强调所有指标带 tenant 维度——但日志里别把 A 的内容打到能被 B 看到的地方。

---

## 5. 避坑与优化

### 5.1 ACL 不能替代租户隔离

**坑**:以为做了 Ch07 的 ACL 就安全了。ACL 管的是"同租户内谁能看哪篇文档",一旦漏了 tenant 过滤,ACL 再严也拦不住跨租户。

**对**:**先隔离租户,再做 ACL**,两层独立。租户隔离在最外层(数据库 RLS / collection),ACL 在其内。

### 5.2 缓存是最隐蔽的串租户源头

**坑**:answer 缓存、embedding 缓存的 key 只用了 query 文本 hash——租户 A 和 B 问同一句话,B 命中了 A 的缓存答案。

**对**:所有缓存 key 强制带 tenant 前缀。把拼 key 的逻辑收口到 `TenantCacheKey` 一处,禁止散落手写。

### 5.3 后台/批处理任务最容易忘 tenant

**坑**:定时重建索引、数据迁移、导出任务往往用 admin 身份跑,`AgentContext` 缺失,tenant 过滤失效,一把捞全量。

**对**:后台任务也必须显式**按 tenant 循环**处理,或在 admin 通道加更严格的审计与二次确认。绝不允许"无 tenant 的全量查询"存在于代码库。

### 5.4 "共享的知识"如何处理

**坑**:有些内容(如平台公告、通用 FAQ)是所有租户共享的,硬塞进每个租户会冗余。

**对**:引入一个保留的**系统租户(如 `tenant:__shared__`)**,检索时按 `tenant IN (当前, __shared__)` 查询。让"共享"成为显式、可控的特例,而非隔离规则的漏洞。

### 5.5 隔离的性能与成本平衡

**坑**:给每个小租户都开独立 collection / schema,几千个租户后元数据爆炸、连接池耗尽。

**对**:分层——大客户 Silo,长尾 Pool 共享 + RLS。用租户元数据表记录每个租户的隔离模式,路由层据此选择数据源。

---

## 本章小结

- **数据串租户是 SaaS 最高红线**,一次事故足以致命;知识库 Agent 因"检索+生成"尤其危险。
- 隔离三层次:Silo(物理)/ Bridge(独立库)/ Pool(行级),企业级常用**混合模式**。
- Pool 模式的精髓:**让"忘记带 tenant"在架构层面不可能**——靠 `TenantAwareRepository` 基类强制、RLS 数据库兜底、`TenantCacheKey` 收口,而非开发者自觉。
- **纵深防御**:租户隔离(最外)→ ACL(次外)→ `CrossTenantGuard` 运行时保险丝,每层都假设上层可能失守。
- 避坑核心:ACL 不替代隔离、缓存必带前缀、后台任务不漏 tenant、共享知识用系统租户显式处理、按规模分层平衡成本。

租户的"隔离"打通了,下一步就是租户的"经营"——下一章 **Chapter 16 · SaaS 化与计量计费**,把多租户平台变成可持续的商业产品:用量计量、配额限流、套餐分级。