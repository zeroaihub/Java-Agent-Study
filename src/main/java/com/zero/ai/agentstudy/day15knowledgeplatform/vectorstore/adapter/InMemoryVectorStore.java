package com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddingVector;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.port.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储（暴力扫描实现）。
 *
 * <p><b>为什么敢用暴力扫描（brute-force）？</b>
 * 因为它算出的是<b>精确</b>最近邻，而 pgvector 的 HNSW / IVFFlat 是<b>近似</b>的。
 * 在教学与测试场景里，精确结果才是可信的基线（golden baseline）——
 * 你无法判断「召回变差了」是因为算法错、还是因为 ANN 索引的召回率本来就不到 100%。
 * 生产环境换 HNSW 时，也应该先用本实现跑出基线，再对比 ANN 的召回率损失。</p>
 *
 * <p><b>并发安全</b>：用 {@link ConcurrentHashMap}，写入与检索都是线程安全的。
 * 但检索期间的快照是「弱一致」的——遍历时若有并发写入，可能看到部分新数据。
 * 对知识检索这种场景，弱一致完全可接受（大不了下次查询再看到）。</p>
 *
 * <p><b>局限（诚实说明）</b>：O(n·d) 复杂度，万级向量尚可，百万级必须换索引。
 * 且进程重启即全部丢失——这正是第 13 章要引入持久化的原因。</p>
 *
 * @author ZeroAi
 */
public class InMemoryVectorStore implements VectorStore {

    /** key = tenantId + "|" + stableKey，保证跨租户永不串号。 */
    private final Map<String, EmbeddedChunk> store = new ConcurrentHashMap<>();

    @Override
    public void upsert(List<EmbeddedChunk> chunks) {
        KnowledgeException.requireNonNull(chunks, "chunks");

        // 写入前统一校验：模型与维度必须与库内已有向量一致。
        // 这是「向量漂移」最有效的防线——在写入口就拒绝混进异种向量，
        // 而不是等到检索质量莫名其妙下降后再排查。
        String incomingModel = chunks.isEmpty() ? null : chunks.get(0).vector().model();
        for (EmbeddedChunk c : chunks) {
            if (incomingModel != null && !incomingModel.equals(c.vector().model())) {
                throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                        "同一批次内出现不同模型的向量：" + incomingModel + " vs " + c.vector().model());
            }
            store.put(tenantKey(c), c);
        }
    }

    @Override
    public int deleteByDocument(TenantId tenantId, DocumentId documentId) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(documentId, "documentId");
        String prefix = tenantId.value() + "|" + documentId.value() + "#";
        List<String> toRemove = new ArrayList<>();
        for (String key : store.keySet()) {
            if (key.startsWith(prefix)) {
                toRemove.add(key);
            }
        }
        toRemove.forEach(store::remove);
        return toRemove.size();
    }

    @Override
    public int deleteByKnowledgeBase(TenantId tenantId, KnowledgeBaseId knowledgeBaseId) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        int removed = 0;
        for (var it = store.values().iterator(); it.hasNext(); ) {
            EmbeddedChunk c = it.next();
            if (c.chunk().tenantId().equals(tenantId)
                    && c.chunk().knowledgeBaseId().equals(knowledgeBaseId)) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    @Override
    public List<VectorMatch> search(TenantId tenantId,
                                    Set<KnowledgeBaseId> knowledgeBaseIds,
                                    EmbeddingVector query,
                                    int topK) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(query, "queryVector");
        if (topK <= 0) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "topK 必须为正");
        }
        boolean scopeLimited = knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty();

        List<VectorMatch> matches = new ArrayList<>();
        for (EmbeddedChunk c : store.values()) {
            // ---- 作用域前置过滤：先过滤，再算相似度 ----
            // 顺序很重要：过滤是廉价的字段比较，相似度是 O(d) 的浮点运算。
            // 先过滤可以省掉绝大部分无效计算，这在向量库里就是真金白银的延迟。
            if (!c.chunk().tenantId().equals(tenantId)) {
                continue;
            }
            if (scopeLimited && !knowledgeBaseIds.contains(c.chunk().knowledgeBaseId())) {
                continue;
            }
            double score = c.vector().cosineSimilarity(query);
            matches.add(new VectorMatch(c, score));
        }

        matches.sort(Comparator.comparingDouble(VectorMatch::score).reversed());
        if (matches.size() > topK) {
            return List.copyOf(matches.subList(0, topK));
        }
        return List.copyOf(matches);
    }

    @Override
    public long count() {
        return store.size();
    }

    /**
     * 仅在测试/运维中使用的全量清空。
     */
    public void clear() {
        store.clear();
    }

    private String tenantKey(EmbeddedChunk c) {
        return c.chunk().tenantId().value() + "|" + c.stableKey();
    }
}
