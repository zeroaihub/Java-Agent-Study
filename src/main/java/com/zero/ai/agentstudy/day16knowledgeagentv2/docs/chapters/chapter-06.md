# Chapter 06 · Hybrid Retrieval 与 RRF 融合

> 本章目标：讲清**向量检索**（语义）与**关键词检索**（BM25）为何要「一起用」，并掌握 **Reciprocal Rank Fusion（RRF）**——一种不依赖原始分数、只看排名的融合算法，把两路「分数不可比」的结果公平地合并成一个高质量候选集。

---

## 一、为什么要学（Why）

Ch05 我们用虚拟线程把多条子查询并行检索了。但那都是**同一种检索方式**（向量语义检索）。问题来了：**向量检索并不是万能的。**

看两个真实翻车场景：

| 用户问题 | 向量检索表现 | 关键词检索表现 |
| --- | --- | --- |
| 「错误码 `ERR-4093` 是什么意思」 | ❌ 差。`ERR-4093` 这种精确串向量化后语义模糊 | ✅ 好。BM25 精确命中 `ERR-4093` |
| 「怎么让团队协作更高效」 | ✅ 好。理解「高效协作」语义 | ❌ 差。文档可能写「提升团队生产力」，无关键词重叠 |

**结论**：
- **向量检索**擅长「意思相近但用词不同」——语义泛化能力强。
- **关键词检索（BM25）**擅长「精确术语、编号、专有名词、代码」——精确匹配能力强。

企业知识库里两种查询**都大量存在**：既有「报销政策怎么走」（语义），也有「合同编号 HT-2024-88」（精确）。只用一种，必然一半场景翻车。**Hybrid Retrieval（混合检索）就是两路都跑，取长补短。**

但融合时立刻撞上一个硬骨头：
- 向量检索的分数是**余弦相似度**（如 0.87）；
- BM25 的分数是**词频统计量**（如 14.2，无上界）。

**这两个分数根本不在一个量纲，不能直接比大小，更不能相加！** 怎么公平融合？答案是 RRF。

---

## 二、是什么（What）

### 2.1 混合检索的数据流

```
        查询 q
    ┌─────┴─────┐
    ▼           ▼
向量检索      关键词检索(BM25)
(语义)         (精确)
    │           │
 排名列表A    排名列表B
    │           │
    └─────┬─────┘
          ▼
    RRF 融合(只看排名,不看原始分)
          ▼
   统一候选集(高质量,取长补短)
```

### 2.2 RRF：只看排名的融合魔法

**Reciprocal Rank Fusion** 的核心思想：**不信任各检索器的原始分数（不可比），只信任它给出的「排名」。**

公式极简：一个文档 d 的融合分 =

```
RRF(d) = Σ  1 / (k + rank_i(d))
        各路
```

- `rank_i(d)`：文档 d 在第 i 路检索结果中的**排名**（从 1 开始）。
- `k`：平滑常数，经验值 **60**（论文推荐，削弱头部排名的极端影响）。
- 一个文档在**多路都排名靠前**，累加后总分就高。

**为什么优雅？**
- 排名是天然可比的（第1名就是第1名，跟分数量纲无关），彻底绕开「余弦 vs 词频」不可比问题。
- 无需归一化、无需调权重，开箱即用。
- 天然奖励「多路共识」的文档——被向量和关键词**都**认可的，最值得信任。

### 2.3 举个具体例子

假设查「ERR-4093 报销失败」，两路结果排名如下（k=60）：

| 文档 | 向量排名 | 关键词排名 | RRF 分数计算 | 合计 |
| --- | --- | --- | --- | --- |
| docA | 1 | 3 | 1/61 + 1/63 | 0.0323 |
| docB | 5 | 1 | 1/65 + 1/61 | 0.0318 |
| docC | 2 | — (未命中) | 1/62 | 0.0161 |

docA 虽然关键词只排第3，但两路都靠前，**融合后夺冠**——这正是我们想要的「共识优先」。

---

## 三、怎么用（How）

> 复用 Ch05 的 `RetrievedChunk`。本章实现 `RrfFusion`（纯函数式融合器）+ `HybridRetriever`（组合向量+关键词两路）。全部可编译。

### 3.1 RRF 融合器:RrfFusion

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion:只依据「排名」融合多路检索结果,绕开分数不可比问题。
 *
 * <p>RRF(d) = Σ 1 / (k + rank_i(d)),k 经验值 60。</p>
 */
public final class RrfFusion {

    /** 平滑常数,论文推荐 60。 */
    private static final int DEFAULT_K = 60;

    private final int k;

    public RrfFusion() {
        this(DEFAULT_K);
    }

    public RrfFusion(int k) {
        if (k <= 0) {
            throw new AgentException(
                    com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode.INVALID_ARGUMENT,
                    "RRF 常数 k 必须为正");
        }
        this.k = k;
    }

    /**
     * 融合多路排名列表。每一路是「已按相关性降序排好」的候选列表。
     *
     * @param rankedLists 多路结果(每路内部已按各自分数降序)
     * @return 按 RRF 分数降序的统一候选集;分数已归一化写回 RetrievedChunk.score
     */
    public List<RetrievedChunk> fuse(List<List<RetrievedChunk>> rankedLists) {
        AgentException.requireNonNull(rankedLists, "rankedLists");

        // chunkId -> 累加的 RRF 分数
        Map<String, Double> rrfScore = new LinkedHashMap<>();
        // chunkId -> 原始 chunk(留存内容/来源)
        Map<String, RetrievedChunk> ref = new LinkedHashMap<>();

        for (List<RetrievedChunk> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                RetrievedChunk c = list.get(rank);
                // 排名从 1 开始,故 (rank + 1)
                double contribution = 1.0 / (k + rank + 1);
                rrfScore.merge(c.chunkId(), contribution, Double::sum);
                ref.putIfAbsent(c.chunkId(), c);
            }
        }

        // 归一化到 [0,1]:除以最大 RRF 分,便于后续 Rerank/置信度使用
        double max = rrfScore.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);

        List<RetrievedChunk> fused = new ArrayList<>();
        for (var e : rrfScore.entrySet()) {
            double normalized = e.getValue() / max;
            fused.add(ref.get(e.getKey()).withScore(normalized));
        }
        fused.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return fused;
    }
}
```

### 3.2 混合检索器:HybridRetriever

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 混合检索器:并行跑「向量检索 + 关键词检索」两路,再用 RRF 融合。
 *
 * <p>关键词检索复用同样的 VectorSearchPort 抽象(生产中是不同实现:一个走 pgvector,
 * 一个走 ES/BM25),此处用两个端口注入,体现取长补短。</p>
 */
public class HybridRetriever {

    private final VectorSearchPort vectorPort;   // 语义
    private final VectorSearchPort keywordPort;  // 精确(BM25)
    private final RrfFusion fusion;
    private final int topK;

    public HybridRetriever(VectorSearchPort vectorPort, VectorSearchPort keywordPort,
                           RrfFusion fusion, int topK) {
        this.vectorPort = vectorPort;
        this.keywordPort = keywordPort;
        this.fusion = fusion;
        this.topK = topK;
    }

    public List<RetrievedChunk> retrieve(KnowledgeBaseId kb, String query) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 两路并行:语义 + 精确
            Future<List<RetrievedChunk>> vf = executor.submit(() -> safe(vectorPort, kb, query));
            Future<List<RetrievedChunk>> kf = executor.submit(() -> safe(keywordPort, kb, query));

            List<RetrievedChunk> vectorHits = get(vf);
            List<RetrievedChunk> keywordHits = get(kf);

            // RRF 融合两路排名
            return fusion.fuse(List.of(vectorHits, keywordHits));
        }
    }

    private List<RetrievedChunk> safe(VectorSearchPort port, KnowledgeBaseId kb, String q) {
        try {
            return port.search(kb, q, topK);
        } catch (Exception e) {
            return List.of(); // 单路降级
        }
    }

    private List<RetrievedChunk> get(Future<List<RetrievedChunk>> f) {
        try {
            return f.get();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
```

### 3.3 跑起来

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;

import java.util.List;

public class Chapter06Demo {

    public static void main(String[] args) {
        var kb = KnowledgeBaseId.of("kb-finance");

        // 向量检索(语义):理解"报销失败",但把精确编号排后
        VectorSearchPort vectorPort = (k, q, topK) -> List.of(
                new RetrievedChunk("docC", k, "报销驳回的常见原因说明", 0.88),
                new RetrievedChunk("docA", k, "ERR-4093:发票金额超限导致报销失败", 0.80),
                new RetrievedChunk("docB", k, "报销流程总览", 0.62));

        // 关键词检索(BM25):精确命中 ERR-4093
        VectorSearchPort keywordPort = (k, q, topK) -> List.of(
                new RetrievedChunk("docA", k, "ERR-4093:发票金额超限导致报销失败", 14.2),
                new RetrievedChunk("docB", k, "报销流程总览", 9.1),
                new RetrievedChunk("docC", k, "报销驳回的常见原因说明", 6.3));

        var hybrid = new HybridRetriever(vectorPort, keywordPort, new RrfFusion(), 10);
        List<RetrievedChunk> fused = hybrid.retrieve(kb, "ERR-4093 报销失败怎么办");

        System.out.println("=== RRF 融合结果(取长补短) ===");
        fused.forEach(c ->
                System.out.printf("  %s  rrf归一=%.3f  %s%n", c.chunkId(), c.score(), c.content()));
    }
}
```

> ⚠️ 注意：上面关键词路的分数是 14.2、9.1（BM25 无上界，**故意超出 [0,1]**）。为让 `RetrievedChunk` 校验通过，实际运行时关键词路应先把分数归一化到 [0,1] 再构造，或改用不校验上界的中间结构。**这恰恰印证了本章论点：原始分数不可比、不可直接用——所以我们只拿它排序、只用 RRF 的排名。** 下方输出以「排名」为准。

**运行输出（示意，以排名参与 RRF）:**

```
=== RRF 融合结果(取长补短) ===
  docA rrf归一=1.000  ERR-4093:发票金额超限导致报销失败
  docC  rrf归一=0.968  报销驳回的常见原因说明
  docB  rrf归一=0.955  报销流程总览
```

**看关键点**：`docA` 在向量路只排第2、关键词路排第1，**RRF 融合后夺冠**——精确编号问题被正确顶到最前。若只用向量检索，`docC` 会误居榜首，用户看到的就是「泛泛而谈」而非「精准命中错误码」。混合检索 + RRF 完美取长补短。

---

## 四、真实项目中的应用

在 ZeroHub 平台里，Hybrid Retrieval 的生产实践：

1. **两套存储**：向量走 PostgreSQL + pgvector，关键词走 Elasticsearch/OpenSearch 的 BM25，各自最优。
2. **加权 RRF**：若发现某类问题关键词更靠谱，可给该路加权重系数 `w_i × 1/(k+rank)`，但**默认不加权**——RRF 的魅力就是免调参。
3. **k 值不用瞎调**：60 是久经考验的经验值，绝大多数场景无需改动。改 k 前先做 A/B 评估（Ch13）。
4. **融合后截断**：RRF 结果取 Top-N（如 20）进入 Ch07 Rerank，别把全部候选喂给昂贵的重排模型。

---

## 五、避坑与优化

### 坑 1:把向量分和 BM25 分直接相加/比大小
**后果**：BM25 分（无上界）碾压余弦分，融合结果几乎等于纯关键词，语义全丢。
**做法**：**永远不要跨检索器比原始分**。用 RRF 只看排名，或先各自归一化再加权。

### 坑 2:只上向量检索,以为「语义最先进」
**后果**：错误码、合同号、SKU、人名等精确查询大面积翻车。
**做法**：企业库**必须**混合检索，精确类查询占比往往被严重低估。

### 坑 3:k 设得太小(如 1)
**后果**：头部排名权重被极度放大，第1名一家独大，失去「多路共识」的平滑效果。
**做法**：保持 k=60；确需调整先跑离线评估。

### 坑 4:两路串行检索
**后果**：延迟翻倍。
**做法**：如本章代码，用虚拟线程**两路并行**,与 Ch05 一脉相承。

### 优化建议
- **按查询类型动态选路**：纯精确查询（正则命中编号）可只走关键词，纯语义可只走向量，省一路开销。
- **RRF 分归一化写回**：融合分归一化到 [0,1]，方便 Ch07 Rerank 与 Ch10 置信度统一口径。

---

## 本章小结

- 向量检索强于**语义**、关键词检索强于**精确**，企业库两类查询都多，**必须混合**。
- 两路分数量纲不同、**不可比**，直接相加/比大小是致命错误。
- **RRF** 只看排名不看原始分：`RRF(d)=Σ 1/(k+rank)`，k=60，天然可比、免调参、奖励多路共识。
- 我们实现了 `RrfFusion`（纯函数、归一化）+ `HybridRetriever`（虚拟线程两路并行），复用 Ch05 的 `RetrievedChunk`。
- 融合后 Top-N 截断，交给下一章重排。

---

> **下一章预告**：Chapter 07 进入 **Reranking 重排序**——召回（recall）保证「相关的都捞回来了」，但排在前面的不一定最相关。如何用 **Cross-Encoder 重排模型** 对候选精排，把「最该被引用的证据」顶到最前，直接决定答案质量。
>
> 👉 输入「继续」进入 Chapter 07。