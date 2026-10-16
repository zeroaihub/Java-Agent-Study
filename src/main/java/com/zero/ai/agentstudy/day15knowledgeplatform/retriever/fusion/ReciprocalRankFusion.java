package com.zero.ai.agentstudy.day15knowledgeplatform.retriever.fusion;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 倒数排名融合（Reciprocal Rank Fusion, RRF）。
 *
 * <p><b>为什么 Hybrid Search 需要融合算法？</b>
 * 因为向量得分（余弦，值域 [-1,1]）与 BM25 得分（无上界，随语料变化）
 * 的<b>量纲完全不同且不可比</b>。你没法说「余弦 0.83」和「BM25 12.7」谁更相关，
 * 就像没法比较「体重 70kg」和「身高 175cm」谁更大。</p>
 *
 * <p>常见的错误做法是「归一化后加权求和」：
 * {@code score = 0.5 * normalize(cosine) + 0.5 * normalize(bm25)}。
 * 它有三个致命问题：
 * <ol>
 *     <li>归一化要依赖当前这批结果的 min/max，<b>换一批查询，分数含义就变了</b>，
 *         无法跨查询比较，也无法设固定阈值；</li>
 *     <li>min == max 时会除零；</li>
 *     <li>权重 0.5/0.5 是拍脑袋定的，换一批数据就得重调。</li>
 * </ol>
 *
 * <p><b>RRF 的优雅之处：它完全不看分数，只看排名。</b>公式极其简单：</p>
 *
 * <pre>
 *   RRF_score(d) = Σ  1 / (k + rank_i(d))
 *                  i∈路
 * </pre>
 *
 * <p>其中 {@code rank} 从 1 开始，{@code k} 是平滑常数（原论文取 60）。</p>
 *
 * <p><b>为什么 k=60？</b>这个常数来自 RRF 原始论文（Cormack et al., SIGIR 2009）的
 * 实验调优。它的作用是<b>压低头部结果的垄断性</b>：
 * k 越小，第 1 名相对第 2 名的优势越大；k 越大，排名差异被抹得越平。
 * 取 60 意味着「第 1 名（1/61）与第 10 名（1/70）的差距只有约 15%」，
 * 这让<b>多路召回的一致性</b>比单路的高排名更重要——
 * 两路都排第 5（2/65）会超过一路第 1、另一路第 50（1/61 + 1/110）。
 * 这正是我们想要的：<b>两路都认可的，才是真相关。</b></p>
 *
 * @author ZeroAi
 */
public final class ReciprocalRankFusion {

    /** RRF 平滑常数。原论文实验值 60，工业界沿用至今。 */
    public static final int DEFAULT_K = 60;

    private final int k;

    public ReciprocalRankFusion() {
        this(DEFAULT_K);
    }

    public ReciprocalRankFusion(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF 平滑常数 k 必须为正");
        }
        this.k = k;
    }

    /**
     * 融合多路检索结果。
     *
     * @param perRetrieverResults 各路检索器的结果，key 为检索器名称，value 为该路按序排列的结果
     * @return 融合后的结果，按 RRF 得分降序
     */
    public List<RetrievedChunk> fuse(Map<String, List<RetrievedChunk>> perRetrieverResults) {
        if (perRetrieverResults == null || perRetrieverResults.isEmpty()) {
            return List.of();
        }

        // 切片唯一键 → 在各路中的排名
        Map<String, Map<String, Integer>> ranksByChunk = new LinkedHashMap<>();
        // 切片唯一键 → Chunk 实例（任一路拿到即可）
        Map<String, Chunk> chunkByKey = new HashMap<>();

        for (Map.Entry<String, List<RetrievedChunk>> entry : perRetrieverResults.entrySet()) {
            String retrieverName = entry.getKey();
            List<RetrievedChunk> results = entry.getValue();
            if (results == null) {
                continue;
            }
            int rank = 1;
            for (RetrievedChunk rc : results) {
                String key = rc.citation();
                ranksByChunk.computeIfAbsent(key, k2 -> new LinkedHashMap<>())
                        .put(retrieverName, rank);
                chunkByKey.putIfAbsent(key, rc.chunk());
                rank++;
            }
        }

        // 计算 RRF 得分
        List<RetrievedChunk> fused = new ArrayList<>(ranksByChunk.size());
        for (Map.Entry<String, Map<String, Integer>> e : ranksByChunk.entrySet()) {
            String key = e.getKey();
            Map<String, Integer> ranks = e.getValue();

            double score = 0.0;
            for (int r : ranks.values()) {
                score += 1.0 / (k + r);
            }
            Set<String> matchedBy = new LinkedHashSet<>(ranks.keySet());

            fused.add(new RetrievedChunk(chunkByKey.get(key), score,
                    Map.copyOf(ranks), List.copyOf(matchedBy)));
        }

        // 得分相同时，按「被更多路命中」优先，保证输出稳定（便于测试与排障）
        fused.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                .thenComparing((RetrievedChunk rc) -> -rc.matchedBy().size())
                .thenComparing(RetrievedChunk::citation));
        return List.copyOf(fused);
    }
}
