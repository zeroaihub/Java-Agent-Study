package com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.util.Map;

/**
 * 检索命中结果。
 *
 * <p><b>这里最值得注意的设计是 {@code ranks} 字段</b>：它记录了「这条结果在每一路
 * 检索器里各自排第几」。保留原始排名而不是只保留融合后的总分，是 RRF 融合能工作的前提，
 * 也是<b>检索可观测性的关键</b>——当用户反馈「搜不到」时，你一眼就能看出：
 * 是两路都没召回（召回问题），还是一路召回了但被融合规则压低了（融合问题）。</p>
 *
 * <p>另一个要点：{@code score} 是<b>融合后的分数</b>，不是余弦相似度。
 * 不同检索器的原始分数（余弦 0~1 vs BM25 无上界）量纲完全不同，
 * 直接混在一起比较没有意义——这正是我们必须用 RRF 这种「只认排名、不认分数」的
 * 融合算法的原因。</p>
 *
 * @param chunk      命中的切片
 * @param score      融合后的最终得分（越大越相关）
 * @param ranks      该切片在各路检索器中的排名，key 为检索器名称，value 为从 1 开始的排名
 * @param matchedBy  命中了哪些检索器，如 {@code [dense, sparse]}
 * @author ZeroAi
 */
public record RetrievedChunk(Chunk chunk,
                             double score,
                             Map<String, Integer> ranks,
                             java.util.List<String> matchedBy) {

    public RetrievedChunk {
        KnowledgeException.requireNonNull(chunk, "chunk");
        ranks = ranks == null ? Map.of() : Map.copyOf(ranks);
        matchedBy = matchedBy == null ? java.util.List.of() : java.util.List.copyOf(matchedBy);
    }

    /**
     * 是否同时被多路检索器命中。
     *
     * <p>这是一个免费的、极强的质量信号：<b>向量与关键词都认为相关的结果，
     * 大概率真的相关</b>。很多生产系统会把它作为重排的一个特征。</p>
     */
    public boolean isHitByAll() {
        return matchedBy.size() > 1;
    }

    /**
     * 溯源用的引用标识：文档 ID + 切片序号。
     */
    public String citation() {
        return chunk.documentId().value() + "#" + chunk.sequence();
    }

    @Override
    public String toString() {
        return String.format("RetrievedChunk{score=%.4f, %s, by=%s, ranks=%s}",
                score, citation(), matchedBy, ranks);
    }
}
