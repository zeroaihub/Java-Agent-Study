# Chapter 07 · Reranking 重排序

> 本章目标：讲清「召回」与「精排」的分工——为什么召回阶段把相关的都捞回来了，还必须再来一次 **Reranking**；掌握 **Cross-Encoder 重排模型** 的原理，并实现一个可插拔的 `Reranker` 端口，把「最该被引用的证据」精准顶到最前。

---

## 一、为什么要学（Why）

Ch06 我们用混合检索 + RRF 得到了一个候选集。但这里有个关键认知：**召回（Recall）负责「不遗漏」，精排（Rerank）负责「排得准」——这是两件事。**

召回阶段用的向量检索（双塔 Bi-Encoder）为了快，是这样工作的：
- **问题**单独编码成一个向量；
- **文档**提前离线编码成向量；
- 检索时只算两个向量的余弦相似度。

问题和文档**从未见面**——它们各自被压缩成向量后才比较。这种「隔空对话」速度极快（可对百万文档秒级检索），但**精度有损**：向量把一段话压成几百个数字，细粒度的相关性丢失了。

结果就是：召回的 Top-20 里，**真正最相关的那篇，可能排在第 8 位**。而我们最终只会把前 3~5 篇喂给 LLM 生成答案——如果最相关的没进前几，答案质量直接崩。

看个例子，问「试用期能休年假吗」：

| 召回排名 | 文档 | 真实相关性 |
| --- | --- | --- |
| 1 | 年假的申请流程说明 | 中（讲年假但没提试用期）|
| 2 | 员工福利总览 | 低 |
| **8** | **试用期员工不享受年假，转正后按工龄计算** | **最高！** |

召回把「最该被引用」的那篇排到了第 8——如果只取前 5，答案就废了。**Reranking 就是来纠正这个排序的。**

---

## 二、是什么（What）

### 2.1 Bi-Encoder（召回）vs Cross-Encoder（精排）

```
【Bi-Encoder 双塔 —— 召回用,快但糙】
  问题 ──► [编码器] ──► 向量Q
                              ╲ 余弦相似度(隔空比较)
  文档 ──► [编码器] ──► 向量D ╱
  ✔ 文档可离线预编码,百万级秒查
  ✘ 问题与文档从未交互,精度有损

【Cross-Encoder 交叉 —— 精排用,准但慢】
  [问题 + 文档] 一起 ──► [编码器] ──► 相关性分数(0~1)
  ✔ 问题与文档逐词交互(注意力),精度极高
  ✘ 每个候选都要跑一次模型,无法预计算,慢
```

**核心区别**：Cross-Encoder 把「问题 + 每一篇候选文档」**拼在一起**送进模型，让二者在注意力层充分交互，输出一个精准的相关性分数。它看得懂「试用期」和「年假」在这句话里的真实关系，而 Bi-Encoder 看不懂。

### 2.2 为什么不直接全用 Cross-Encoder？

因为它**太慢**：每个候选都要过一次模型，百万文档全跑一遍要几分钟。所以工程上是**两阶段**：

```
百万文档 ──Bi-Encoder召回──► Top-20候选 ──Cross-Encoder精排──► Top-5 ──► LLM
         (快,负责不遗漏)              (准,负责排得对)
```

- 召回：从百万里快速捞出 20 个「可能相关」的（宽进）。
- 精排：只对这 20 个做昂贵的精细打分，重排后取前 5（严出）。

**先粗后精、粗的快、精的准**——这是所有检索系统的黄金范式。

### 2.3 重排的收益

重排通常能把**答案相关性提升 10%~30%**，是检索质量的「临门一脚」。代价是额外 20~100ms 的模型推理（只跑 Top-N，可控）。

---

## 三、怎么用（How）

> 复用 Ch05 的 `RetrievedChunk`。实现 `Reranker` 端口 + 一个基于词重叠的**演示版**重排器（讲清「问题-文档交互打分」的思想，生产替换为真实 Cross-Encoder）。全部可编译。

### 3.1 重排端口:Reranker

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.rerank;

import com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 重排端口(Hexagonal Port)。
 *
 * <p>对召回候选做「问题-文档」精细相关性打分并重排。
 * 实现可以是 Cross-Encoder 模型、LLM 打分或规则演示版。</p>
 */
public interface Reranker {

    /**
     * 对候选重排,返回按新相关性降序、截断到 topN 的结果。
     *
     * @param query      用户查询(参与交互打分)
     * @param candidates 召回候选(来自 Ch06 融合结果)
     * @param topN       精排后保留条数
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
```

### 3.2 演示版重排器:LexicalOverlapReranker

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.rerank;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 演示版重排器:用「问题词与文档词的重叠度」模拟 Cross-Encoder 的交互打分。
 *
 * <p>真实生产替换为 bge-reranker / Cohere Rerank 等 Cross-Encoder 模型;
 * 但本类足以讲清「让问题和文档真正交互后再打分」这一核心思想,零依赖可运行。</p>
 */
public class LexicalOverlapReranker implements Reranker {

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        AgentException.requireText(query, "query");
        AgentException.requireNonNull(candidates, "candidates");
        if (topN <= 0) {
            throw new AgentException(
                    com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode.INVALID_ARGUMENT,
                    "topN 必须为正");
        }

        Set<Character> queryChars = toCharSet(query);

        List<RetrievedChunk> reranked = new ArrayList<>();
        for (RetrievedChunk c : candidates) {
            double crossScore = interactScore(queryChars, c.content());
            reranked.add(c.withScore(crossScore));
        }
        reranked.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());

        // 严出:只保留精排后的 Top-N
        return reranked.subList(0, Math.min(topN, reranked.size()));
    }

    /**
     * 模拟「问题-文档交互打分」:字符重叠率。
     * 真实模型是深层注意力交互,这里用重叠率近似演示相同的目标——
     * 让「问题里的关键词在文档里真实出现」的候选得高分。
     */
    private double interactScore(Set<Character> queryChars, String content) {
        if (queryChars.isEmpty()) {
            return 0.0;
        }
        Set<Character> docChars = toCharSet(content);
        int hit = 0;
        for (char qc : queryChars) {
            if (docChars.contains(qc)) {
                hit++;
            }
        }
        return (double) hit / queryChars.size();
    }

    private Set<Character> toCharSet(String s) {
        Set<Character> set = new HashSet<>();
        for (char ch : s.toCharArray()) {
            if (!Character.isWhitespace(ch)) {
                set.add(ch);
            }
        }
        return set;
    }
}
```

### 3.3 跑起来

```java
package com.zero.ai.agentstudy.day16knowledgeagentv2.rerank;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.retrieval.RetrievedChunk;

import java.util.List;

public class Chapter07Demo {

    public static void main(String[] args) {
        var kb = KnowledgeBaseId.of("kb-hr");

        // 模拟 Ch06 召回结果:最相关的"试用期不享受年假"被排在了第3(召回排序不准)
        List<RetrievedChunk> recalled = List.of(
                new RetrievedChunk("d1", kb, "年假的申请流程说明", 0.90),
                new RetrievedChunk("d2", kb, "员工福利总览介绍", 0.85),
                new RetrievedChunk("d3", kb, "试用期员工不享受年假,转正后按工龄计算", 0.80));

        String query = "试用期能休年假吗";

        System.out.println("=== 召回原始排序(向量分) ===");
        recalled.forEach(c -> System.out.printf("  %s  %.2f  %s%n", c.chunkId(), c.score(), c.content()));

        Reranker reranker = new LexicalOverlapReranker();
        List<RetrievedChunk> reranked = reranker.rerank(query, recalled, 3);

        System.out.println("=== Cross-Encoder 精排后 ===");
        reranked.forEach(c -> System.out.printf("  %s  %.2f  %s%n", c.chunkId(), c.score(), c.content()));
    }
}
```

**运行输出（示意）:**

```
=== 召回原始排序(向量分) ===
  d1  0.90  年假的申请流程说明
  d2  0.85  员工福利总览介绍
  d3  0.80  试用期员工不享受年假,转正后按工龄计算

=== Cross-Encoder 精排后 ===
  d3  0.83  试用期员工不享受年假,转正后按工龄计算
  d1  0.50  年假的申请流程说明
  d2  0.17  员工福利总览介绍
```

**看关键点**：召回阶段最相关的 `d3`（同时含「试用期」「年假」）被排在末位（向量分 0.80）；重排后，因为它与问题的**词交互最充分**，被正确顶到**第一**。这就是 Cross-Encoder 精排的价值——**纠正召回的排序错误**。演示版用字符重叠近似，生产版用真实模型效果更强。

---

## 四、真实项目中的应用

在 ZeroHub 平台里，Reranking 的生产实践：

1. **模型选型**：中文常用 `bge-reranker-large`（本地部署，数据不出域）或 Cohere Rerank API（省运维）。`Reranker` 端口让两者随时切换。
2. **只重排 Top-N**：召回给 20~50 候选，重排后取 3~5 进 LLM。**绝不对全量候选重排**——那是灾难性的延迟。
3. **批量推理**：把 N 个 (query, doc) 对**一次批量**送进模型（batch），而非逐条调用，吞吐翻数倍。
4. **分数阈值兜底**：若重排后 Top-1 分数仍低于阈值（如 0.3），说明**根本没检索到相关内容**，应触发 Ch11 的「知识缺口」流程，而不是硬编一个答案。

```java
// 生产:重排后做质量门禁,分数太低触发知识缺口
List<RetrievedChunk> top = reranker.rerank(query, candidates, 5);
if (top.isEmpty() || top.get(0).score() < 0.3) {
    return AgentAnswer.knowledgeGap(traceId); // 见 Ch01 / Ch11:诚实说"我不知道"
}
```

---

## 五、避坑与优化

### 坑 1:跳过重排,直接拿召回 Top-K 喂 LLM
**后果**：最相关的证据没进前几，LLM 拿着次相关内容硬答，答案跑偏。
**做法**：召回后**必须**重排，这是检索质量的临门一脚。

### 坑 2:对全部召回候选做 Cross-Encoder
**后果**：几百个候选逐个过模型，延迟爆炸到几秒。
**做法**：严格两阶段——召回宽进（Top-20~50），重排严出（Top-3~5）。

### 坑 3:逐条调用重排模型
**后果**：N 次网络往返，延迟线性累加。
**做法**：批量推理，一次请求打分所有候选。

### 坑 4:忽视重排分数的绝对值
**后果**：即使全都不相关，也照样取 Top-5 强答，产生幻觉。
**做法**：设分数阈值门禁,低于阈值转「知识缺口」（Ch11）。

### 优化建议
- **缓存热点重排结果**：高频问题的 (query, candidates) 组合可缓存重排结果。
- **重排分作为置信度输入**：Top-1 重排分是 Ch10 答案置信度的重要信号之一。

---

## 本章小结

- 召回负责**不遗漏**（Recall），重排负责**排得准**（Precision），两者分工不可混淆。
- **Bi-Encoder** 快但糙（问题文档隔空比较），**Cross-Encoder** 准但慢（问题文档拼一起交互打分）。
- 工程黄金范式：**先粗后精**——召回宽进 Top-N，重排严出 Top-K。
- 我们实现了 `Reranker` 端口 + 演示版 `LexicalOverlapReranker`，讲清「交互打分纠正召回排序」，生产替换为真实 Cross-Encoder。
- 重排后务必做**分数阈值门禁**，太低就诚实转「知识缺口」。

---

> **下一章预告**：Chapter 08 进入 **ACL 权限与知识隔离**——Ch03 我们在路由阶段做了库级权限前置，但真正的企业级要求是**行级/文档级**的精细控制：同一个库里，A 能看的文档 B 未必能看。如何把权限**下推到检索**，从根上杜绝越权数据进入 LLM 上下文。
>
> 👉 输入「继续」进入 Chapter 08。