package com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器（Chapter 05 核心）。
 *
 * <p>向量检索擅长「语义相近」，关键词检索擅长「精确命中」，二者各有盲区。混合检索把多个
 * 检索源的结果用 <b>RRF（Reciprocal Rank Fusion，倒数排名融合）</b> 合并：不依赖各源分数的
 * 绝对值（不同源分数不可比），只看「排名」，因此天然稳健。</p>
 *
 * <p>RRF 公式：{@code score(d) = Σ 1 / (k + rank_i(d))}，k 为平滑常数（常取 60），
 * rank 从 1 开始。某文档在越多检索源里排名越靠前，融合后分越高。</p>
 *
 * @author ZeroAi
 */
public class HybridRetriever {

    private static final int RRF_K = 60;

    private final List<RetrievalSource> sources;

    public HybridRetriever(List<RetrievalSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * 多源检索并 RRF 融合。
     *
     * @param ctx          请求上下文
     * @param query        查询
     * @param perSourceTopK 每个源各取多少条
     * @param finalTopK    融合后最终返回多少条
     */
    public List<RetrievedChunk> retrieve(AgentContext ctx, String query, int perSourceTopK, int finalTopK) {
        // chunkId -> 累加的 RRF 分数
        Map<String, Double> fused = new LinkedHashMap<>();
        // chunkId -> 片段本体（保留一份用于最终返回）
        Map<String, RetrievedChunk> pool = new LinkedHashMap<>();

        for (RetrievalSource source : sources) {
            List<RetrievedChunk> hits = source.search(ctx, query, perSourceTopK);
            for (int rank = 0; rank < hits.size(); rank++) {
                RetrievedChunk chunk = hits.get(rank);
                double contribution = 1.0 / (RRF_K + rank + 1); // rank 从 1 开始
                fused.merge(chunk.chunkId(), contribution, Double::sum);
                pool.putIfAbsent(chunk.chunkId(), chunk);
            }
        }

        List<RetrievedChunk> result = new ArrayList<>();
        fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(finalTopK)
                .forEach(e -> result.add(pool.get(e.getKey()).withScore(e.getValue())));
        return result;
    }

    /**
     * 便捷重载：每源取 10，最终返回 finalTopK。
     */
    public List<RetrievedChunk> retrieve(AgentContext ctx, String query, int finalTopK) {
        return retrieve(ctx, query, 10, finalTopK);
    }
}