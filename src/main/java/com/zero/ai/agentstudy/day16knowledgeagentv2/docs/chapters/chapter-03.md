# Chapter 03 · Knowledge Routing 知识路由

> 本章目标：讲清企业多知识库场景下「问题该去哪个库检索」的路由问题，掌握**规则路由、语义路由、混合路由与多库并行**四种策略，并用可运行代码实现一个可插拔的 `KnowledgeRouter`。

---

## 一、为什么要学（Why）

在 Demo 里，你只有一个知识库，问什么都往里检索，天下太平。

但真实企业不是这样。一家中型公司随手就能列出十几个知识库：

```
HR 制度库、财务报销库、法务合同库、研发 Wiki、产品需求库、
客服 FAQ、销售话术库、IT 运维手册、安全合规库、培训课件库……
```

如果不做路由，直接把用户问题往「所有库」检索，会出三个致命问题：

1. **答非所问**：财务同事问「差旅报销上限」，结果检索到研发 Wiki 里「差旅系统的接口文档」——向量相似度高，但完全不是用户要的。
2. **性能爆炸**：每个库都检索一遍，10 个库就是 10 倍延迟与成本，用户等到花儿都谢了。
3. **权限失控**：把问题撒到所有库，等于绕过了「用户只能访问部分库」的边界。

**Knowledge Routing 就是推理链的第一个决策点**：在检索之前，先想清楚「这个问题，到底该问谁」。这一步做对了，后面的检索、Rerank、验证才有意义；做错了，后面全是无用功。

---

## 二、是什么（What）

### 2.1 路由的本质

Knowledge Routing 的输入输出非常清晰：

```
输入: 用户问题 + AgentContext(租户/工作空间/用户/角色)
输出: 一个或多个目标知识库 KnowledgeBaseId（带置信度）
```

注意输出是**「一个或多个」**——有些问题明确属于某个库（「报销上限」→ 财务库），有些问题跨库（「新员工入职要办哪些手续」→ HR + IT + 安全），需要多库并行。

### 2.2 四种路由策略

| 策略 | 原理 | 优点 | 缺点 | 适用 |
| --- | --- | --- | --- | --- |
| **规则路由** | 关键词 / 正则 / 元数据匹配 | 快、准、可解释、零成本 | 覆盖有限，需人工维护规则 | 高频、明确的问题 |
| **语义路由** | 问题 Embedding 与库描述 Embedding 算相似度 | 覆盖广，无需硬编码 | 有成本，边界模糊时会错 | 长尾、口语化问题 |
| **LLM 路由** | 让 LLM 判断问题归属 | 最灵活，能理解意图 | 最慢最贵 | 复杂、需推理的问题 |
| **混合路由** | 规则优先，未命中再语义/LLM 兜底 | 兼顾速度与覆盖 | 实现略复杂 | **生产推荐** |

### 2.3 混合路由的决策流

生产环境几乎都用混合路由，因为它把「快而准」和「广而灵活」结合起来：

```
用户问题
   │
   ▼
[1] 规则路由：命中关键词/元数据？
   │命中 ──────────────▶ 直接返回目标库（毫秒级，零成本）
   │未命中
   ▼
[2] 语义路由：问题向量 vs 各库描述向量，Top-K 相似度
   │相似度 ≥ 阈值 ─────▶ 返回 Top-K 库
   │全部 < 阈值
   ▼
[3] LLM 路由（可选）：让模型判断，或直接判定「无匹配库」
   │
   ▼
   命中知识缺口 → 走 Knowledge Gap（Ch12）
```

### 2.4 多库并行策略

当路由返回多个库时，有两种做法：

- **Fan-out（扇出并行）**：对每个库并行检索，再合并结果。Java 21 的**虚拟线程**让这一步几乎零成本——每个库一个虚拟线程，等所有结果回来再融合（融合算法见 Ch06 的 RRF）。
- **权重路由**：不同库给不同权重（如「财务库 0.8 + 通用库 0.2」），检索后按权重加权融合。

### 2.5 路由必须尊重权限

**关键铁律**：路由候选库,必须先用 `AgentContext` 的角色过滤。一个只有 `EMPLOYEE` 角色的用户,即便问题语义上匹配「高管薪酬库」,该库也不能进入路由结果。**路由是权限的第一道闸,不是最后一道。**

---

## 三、怎么用（How）

> 本章实现一个可插拔的 `KnowledgeRouter` 端口 + 混合路由实现,代码可独立编译运行。

### 3.1 定义路由结果:RouteDecision

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.router;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;

/**
 * 单个知识库的路由决策（带置信度与命中理由）。
 *
 * @param knowledgeBaseId 目标知识库
 * @param score           路由置信度 [0,1]
 * @param reason          命中理由（规则名 / 语义相似度），用于可解释与调试
 */
public record RouteDecision(KnowledgeBaseId knowledgeBaseId, double score, String reason) {

    public RouteDecision {
        AgentException.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        if (score < 0 || score > 1) {
            throw new AgentException(
                    com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode.INVALID_ARGUMENT,
                    "score 必须在 [0,1] 之间: " + score
            );
        }
    }
}
```

### 3.2 定义路由端口:KnowledgeRouter

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.router;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.List;

/**
 * 知识路由端口（Hexagonal Port）。
 *
 * <p>推理链的第一个决策节点:根据问题与上下文,判断该去哪些知识库检索。
 * 可有多种实现:规则路由 / 语义路由 / LLM 路由 / 混合路由。</p>
 */
public interface KnowledgeRouter {

    /**
     * 路由决策。
     *
     * @param question 用户问题
     * @param context  上下文（用于权限过滤）
     * @return 按置信度降序的目标知识库列表;为空表示命中知识缺口
     */
    List<RouteDecision> route(String question, AgentContext context);
}
```

### 3.3 定义知识库元信息:KnowledgeBaseMeta

路由需要知道「有哪些库、每个库管什么、需要什么角色」。

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.router;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;

import java.util.List;
import java.util.Set;

/**
 * 知识库元信息(路由的判断依据)。
 *
 * @param id          知识库 ID
 * @param name        名称(如「财务报销库」)
 * @param keywords    规则路由关键词
 * @param requiredRole 访问所需角色(权限第一道闸);null 表示所有人可访问
 */
public record KnowledgeBaseMeta(
        KnowledgeBaseId id,
        String name,
        List<String> keywords,
        String requiredRole
) {
    /** 当前用户是否有权访问本库。 */
    public boolean accessibleBy(Set<String> roles) {
        return requiredRole == null || roles.contains(requiredRole) || roles.contains("ADMIN");
    }
}
```

### 3.4 实现混合路由:HybridKnowledgeRouter

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.router;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 混合路由:规则优先,未命中再语义兜底。
 *
 * <p>本章语义部分用「关键词重叠度」模拟真实 Embedding 相似度,
 * 真实实现会在 Ch06 接入向量模型。所有候选库都先经过权限过滤。</p>
 */
public class HybridKnowledgeRouter implements KnowledgeRouter {

    private final List<KnowledgeBaseMeta> catalog;
    private final double semanticThreshold;

    public HybridKnowledgeRouter(List<KnowledgeBaseMeta> catalog, double semanticThreshold) {
        this.catalog = AgentException.requireNonNull(catalog, "catalog");
        this.semanticThreshold = semanticThreshold;
    }

    @Override
    public List<RouteDecision> route(String question, AgentContext context) {
        AgentException.requireText(question, "question");
        var roles = context.roles();

        // 第一步:权限过滤——越权的库根本不进入候选
        List<KnowledgeBaseMeta> accessible = catalog.stream()
                .filter(kb -> kb.accessibleBy(roles))
                .toList();

        // 第二步:规则路由——关键词命中即高置信直返
        List<RouteDecision> ruleHits = new ArrayList<>();
        for (KnowledgeBaseMeta kb : accessible) {
            for (String kw : kb.keywords()) {
                if (question.contains(kw)) {
                    ruleHits.add(new RouteDecision(kb.id(), 0.95, "规则命中:" + kw));
                    break;
                }
            }
        }
        if (!ruleHits.isEmpty()) {
            return ruleHits;
        }

        // 第三步:语义路由(此处用关键词重叠度模拟相似度)
        List<RouteDecision> semanticHits = new ArrayList<>();
        for (KnowledgeBaseMeta kb : accessible) {
            double sim = similarity(question, kb.keywords());
            if (sim >= semanticThreshold) {
                semanticHits.add(new RouteDecision(kb.id(), sim, "语义相似度:" + sim));
            }
        }
        semanticHits.sort(Comparator.comparingDouble(RouteDecision::score).reversed());

        // 第四步:全部未命中 → 返回空,交由 Knowledge Gap 处理(Ch12)
        return semanticHits;
    }

    /** 模拟语义相似度:关键词在问题中的覆盖比例。真实实现用向量余弦相似度。 */
    private double similarity(String question, List<String> keywords) {
        if (keywords.isEmpty()) {
            return 0;
        }
        long hit = keywords.stream().filter(question::contains).count();
        return (double) hit / keywords.size();
    }
}
```

### 3.5 跑起来

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.router;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.List;
import java.util.Set;

public class Chapter03Demo {

    public static void main(String[] args) {
        // 知识库目录:财务库(公开)、高管薪酬库(需 EXEC 角色)
        var catalog = List.of(
                new KnowledgeBaseMeta(KnowledgeBaseId.of("kb-finance"), "财务报销库",
                        List.of("报销", "差旅", "发票"), null),
                new KnowledgeBaseMeta(KnowledgeBaseId.of("kb-hr"), "HR 制度库",
                        List.of("休假", "考勤", "入职"), null),
                new KnowledgeBaseMeta(KnowledgeBaseId.of("kb-exec"), "高管薪酬库",
                        List.of("薪酬", "股权", "期权"), "EXEC")
        );

        var router = new HybridKnowledgeRouter(catalog, 0.3);

        // 普通员工问报销 → 命中财务库
        var ctx = AgentContext.of(TenantId.of("t1"), WorkspaceId.of("w1"),
                UserId.of("u1"), Set.of("EMPLOYEE"));
        System.out.println("Q1: " + router.route("差旅报销的上限是多少", ctx));

        // 普通员工问高管薪酬 → 无权访问该库,路由为空(命中知识缺口)
        System.out.println("Q2: " + router.route("高管的股权激励方案", ctx));
    }
}
```

**运行输出（示意）:**

```
Q1: [RouteDecision[knowledgeBaseId=kb-finance, score=0.95, reason=规则命中:报销]]
Q2: []
```

**看关键点**:Q2 中普通员工问「高管股权」,虽然语义上匹配「高管薪酬库」,但因为**权限过滤在路由第一步就把该库排除了**,结果为空——越权数据从一开始就不可见,而不是「先检索再过滤」。这就是「权限前置」原则在路由层的落地。

---

## 四、真实项目中的应用

在 ZeroHub 平台里,`KnowledgeRouter` 是这样落地的:

1. **规则来自配置中心**:关键词、正则不硬编码,放在配置中心/数据库,运营可动态调整,无需发版。
2. **语义路由用真实 Embedding**:每个库维护一段「库描述」并预先算好 Embedding,路由时把问题 Embedding 与各库描述算余弦相似度,取 Top-K。
3. **多库并行用虚拟线程**:路由返回多个库时,用 `Executors.newVirtualThreadPerTaskExecutor()` 并行检索,延迟不随库数量线性增长。
4. **路由决策全程 Trace**:每次路由的命中理由(规则名/相似度)都记入 Trace,线上「路由错了」时可秒级复盘。

```java
// 多库并行检索(虚拟线程),真实检索逻辑见 Ch06
try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = decisions.stream()
            .map(d -> executor.submit(() -> retrieve(d.knowledgeBaseId(), query, ctx)))
            .toList();
    // 收集所有库的检索结果,交给 Ch06 的 RRF 融合
}
```

---

## 五、避坑与优化

### 坑 1:不做路由,全库检索
**后果**:答非所问 + 延迟成本爆炸 + 权限失控。
**做法**:路由是推理链第一个节点,必须做。哪怕只有一个库,也应显式路由以便未来扩展。

### 坑 2:权限过滤放在检索之后
**后果**:越权库进入了路由与检索,泄漏风险。
**做法**:权限过滤必须是路由的**第一步**(见 3.4),越权库根本不进候选。

### 坑 3:只用 LLM 路由
**后果**:每个问题都调一次 LLM,慢且贵,高频问题被拖垮。
**做法**:混合路由——规则先拦截 80% 高频问题(毫秒级),剩下的才走语义/LLM。

### 坑 4:语义阈值拍脑袋
**后果**:阈值太高漏召库,太低乱召库。
**做法**:用真实问题日志离线评测,画准召率曲线找拐点;并结合 Ch15 的 Evaluation 持续调优。

### 优化建议
- **路由结果缓存**:高频问题的路由结果可缓存(Redis),命中直接返回,避免重复计算。
- **兜底策略**:全部未命中时,不要静默返回空,而应显式进入 Knowledge Gap 流程(Ch12),给用户明确反馈。

---

## 本章小结

- Knowledge Routing 是推理链的**第一个决策点**:在检索前判断「问题该去哪些库」。
- 四种策略:规则(快准)、语义(广)、LLM(灵活)、**混合(生产推荐)**。
- **权限过滤必须前置**:越权库在路由第一步就被排除,落地「权限前置」原则。
- 多库场景用 Java 21 **虚拟线程 Fan-out** 并行检索,延迟不随库数量爆炸。
- 我们实现了可插拔的 `KnowledgeRouter` 端口 + `HybridKnowledgeRouter`,规则优先、语义兜底、权限前置。

---

> **下一章预告**:Chapter 04 进入 **Query Rewrite 与 Query Decomposition**——用户问题往往口语化、含糊、甚至一句话包含多个子问题,如何在检索前把它「翻译」成机器好检索的规范化查询。
>
> 👉 输入「继续」进入 Chapter 04。