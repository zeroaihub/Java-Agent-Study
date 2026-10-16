package com.zero.ai.agentstudy.day15knowledgeplatform.admin;

import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.EmbeddingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.Bm25Index;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.KnowledgeIndexer;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.port.VectorStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台统计服务（管理端数据源）。
 *
 * <p><b>为什么需要它？</b>
 * 因为知识平台是一个<b>需要持续运营</b>的系统，而不是部署完就不管的软件。
 * 运维需要回答的问题很多，而且都无法靠"看代码"回答：</p>
 * <ul>
 *     <li>这个租户现在有多少切片？配额还剩多少？</li>
 *     <li>Embedding 缓存命中率多少？有没有在重复烧钱？</li>
 *     <li><b>向量库与倒排索引的数量一致吗？</b>（不一致说明历史上发生过双写失败）</li>
 * </ul>
 *
 * <p><b>最后一条是本类最重要的价值</b>：把「索引一致性」做成一个可以随时查的指标。
 * 第 7 章说过，Hybrid Search 要求同一个切片同时存在于两套索引里，
 * 而双写天然有不一致的风险。如果不主动监控，
 * 这种不一致会一直潜伏，直到某天用户反馈"搜不到某份文档"才被发现——
 * 而那时已经无从追溯是哪一次写入失败导致的了。</p>
 *
 * @author ZeroAi
 */
public class PlatformStatsService {

    private final KnowledgeIndexer indexer;
    private final EmbeddingService embeddingService;

    public PlatformStatsService(KnowledgeIndexer indexer, EmbeddingService embeddingService) {
        this.indexer = indexer;
        this.embeddingService = embeddingService;
    }

    /**
     * 生成平台统计快照。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> stats = new LinkedHashMap<>();

        VectorStore vectorStore = indexer.vectorStore();
        Bm25Index bm25 = indexer.bm25Index();

        long vectorCount = vectorStore.count();
        int bm25Count = bm25.size();
        int registryCount = indexer.indexedChunkCount();

        stats.put("vectorCount", vectorCount);
        stats.put("bm25Count", bm25Count);
        stats.put("registryCount", registryCount);

        // 索引一致性：这是最应该被监控的指标
        boolean consistent = (vectorCount == bm25Count) && (bm25Count == registryCount);
        stats.put("indexConsistent", consistent);
        if (!consistent) {
            stats.put("inconsistencyHint", String.format(
                    "向量库 %d / 倒排索引 %d / 反查表 %d —— 三者必须相等，"
                            + "不相等说明发生过双写失败，需要重新索引", vectorCount, bm25Count, registryCount));
        }

        // Embedding 成本指标
        stats.put("embeddingModel", embeddingService.modelId());
        stats.put("embeddingTotal", embeddingService.totalEmbedded());
        stats.put("embeddingCacheHits", embeddingService.cacheHits());
        stats.put("embeddingBatchInvocations", embeddingService.batchInvocations());
        stats.put("embeddingCacheHitRate",
                String.format("%.1f%%", embeddingService.cacheHitRate() * 100));

        return stats;
    }

    /**
     * 索引一致性检查（供定时任务调用）。
     */
    public boolean isIndexConsistent() {
        return indexer.vectorStore().count() == indexer.bm25Index().size()
                && indexer.bm25Index().size() == indexer.indexedChunkCount();
    }
}
