package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.KnowledgeChunk;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.RetrievalResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库的内存实现（适配器）——余弦相似度暴力检索。
 *
 * <p>以 {@code tenantId -> List<KnowledgeChunk>} 组织切片，天然实现<strong>多租户隔离</strong>：
 * 检索只在当前租户的切片集合内进行。检索算法为「暴力最近邻」——遍历该租户全部切片，
 * 逐一计算与查询向量的<strong>余弦相似度</strong>，取 topK。</p>
 *
 * <p>这与生产环境 pgvector 的 {@code <=>} 余弦距离算子在语义上完全一致，
 * 区别仅在于向量库有 HNSW/IVF 等索引做近似最近邻（ANN）加速。切换到 pgvector 时，
 * 只需替换本类为 JDBC/JPA 适配器，实现同一 {@link KnowledgeStore} 端口即可。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Repository
public class InMemoryKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryKnowledgeStore.class);

    /** 租户 -> 该租户的所有切片。 */
    private final ConcurrentHashMap<String, List<KnowledgeChunk>> store = new ConcurrentHashMap<>();

    @Override
    public void saveAll(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (KnowledgeChunk chunk : chunks) {
            store.computeIfAbsent(chunk.tenantId(), k -> new ArrayList<>()).add(chunk);
        }
        log.debug("知识入库: tenant={}, chunks={}", chunks.get(0).tenantId(), chunks.size());
    }

    @Override
    public List<RetrievalResult> search(String tenantId, float[] queryVector, int topK) {
        List<KnowledgeChunk> chunks = store.get(tenantId);
        if (chunks == null || chunks.isEmpty() || queryVector == null || topK <= 0) {
            return List.of();
        }
        List<RetrievalResult> results = new ArrayList<>(chunks.size());
        // 在快照上遍历，避免并发修改；对每个已向量化切片计算余弦相似度
        for (KnowledgeChunk chunk : List.copyOf(chunks)) {
            if (!chunk.hasEmbedding()) {
                continue;
            }
            double score = cosineSimilarity(queryVector, chunk.embedding());
            results.add(new RetrievalResult(chunk, score));
        }
        // RetrievalResult 已按相似度降序实现 Comparable
        results.sort(null);
        return results.size() > topK ? new ArrayList<>(results.subList(0, topK)) : results;
    }

    @Override
    public void deleteByDocument(String tenantId, String documentId) {
        List<KnowledgeChunk> chunks = store.get(tenantId);
        if (chunks == null) {
            return;
        }
        synchronized (chunks) {
            chunks.removeIf(c -> c.documentId().equals(documentId));
        }
        log.debug("删除文档切片: tenant={}, doc={}", tenantId, documentId);
    }

    @Override
    public long count(String tenantId) {
        List<KnowledgeChunk> chunks = store.get(tenantId);
        return chunks == null ? 0 : chunks.size();
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * <p>余弦相似度 = 点积 / (模长乘积)，取值约 [-1, 1]，越接近 1 越相似。
     * 若向量已 L2 归一化（本教程的向量化实现即如此），则点积本身就是余弦相似度。</p>
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}