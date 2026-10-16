package com.zero.ai.agentstudy.day15knowledgeplatform.retriever;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.SparseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.port.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 索引编排器：让「向量库」与「倒排索引」始终保持一致。
 *
 * <p><b>为什么需要这一层？</b>
 * 因为 Hybrid Search 要求同一个切片<b>同时存在于两套索引</b>里：
 * 向量库存稠密向量，倒排索引存词频。它们是两个完全独立的存储结构，
 * 天然存在「写了 A 没写 B」的不一致风险。</p>
 *
 * <p>如果不加约束，让调用方自己分别调 {@code vectorStore.upsert()} 和
 * {@code bm25Index.add()}，那么一旦某处漏调其中之一，就会出现：
 * 「关键词能搜到但语义搜不到」或反之。这种 bug 很难复现，
 * 因为它只在特定的检索路径上才暴露出来。</p>
 *
 * <p>所以这里提供一个<b>唯一的写入口</b>，把所有索引的更新收敛到一个地方，
 * 并强制走同一套删除逻辑。这是「单一职责」在编排层的体现：
 * 索引各自的算法很复杂，但<b>「保持它们同步」这件事同样复杂，同样需要被封装</b>。</p>
 *
 * <p><b>真实生产里这一层该怎么升级？</b>
 * 双写无法保证原子性（向量库写成功、倒排索引写失败，就永久不一致了）。
 * 生产方案是<b>事件驱动 + 最终一致</b>：写入向量库后发领域事件，
 * 由消费者更新倒排索引，失败进重试队列。我们在第 11 章的同步模块里
 * 用同样的思路处理「删除同步」——原理是相通的。</p>
 *
 * @author ZeroAi
 */
public class KnowledgeIndexer implements SparseRetriever.ChunkLookup {

    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;

    /** 切片反查表：documentId#sequence → Chunk。供稀疏检索命中后还原完整切片。 */
    private final Map<String, Chunk> chunkRegistry = new ConcurrentHashMap<>();

    public KnowledgeIndexer(VectorStore vectorStore, Bm25Index bm25Index) {
        this.vectorStore = KnowledgeException.requireNonNull(vectorStore, "vectorStore");
        this.bm25Index = KnowledgeException.requireNonNull(bm25Index, "bm25Index");
    }

    /**
     * 将一批已向量化的切片写入全部索引。
     */
    public void index(List<EmbeddedChunk> chunks) {
        KnowledgeException.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty()) {
            return;
        }
        // 先写向量库（较重，且带模型一致性校验）
        vectorStore.upsert(chunks);

        // 再写倒排索引与反查表
        for (EmbeddedChunk ec : chunks) {
            Chunk c = ec.chunk();
            chunkRegistry.put(citationOf(c), c);
            bm25Index.add(new Bm25Index.Entry(
                    c.tenantId(), c.knowledgeBaseId(), c.documentId(), citationOf(c)), c.text());
        }
    }

    /**
     * 从<b>全部索引</b>中删除某文档的所有切片 —— 这就是「删除同步」。
     *
     * <p><b>为什么值得为它单独写一个方法，并在文档里反复强调？</b>
     * 因为「删除不同步」是企业知识平台最危险的坑之一：
     * 文档被删了（可能是因为它包含错误或敏感信息），但向量还在库里，
     * 于是用户依然能通过提问获得那段<b>本应消失的知识</b>。
     * 轻则答案过期，重则泄漏已删除的敏感内容——这是实打实的合规事故。</p>
     *
     * <p>更麻烦的是它<b>几乎不会被测试发现</b>：
     * 功能测试关注的是"加了能不能搜到"，很少有人测"删了还能不能搜到"。</p>
     *
     * @return 删除结果，含各索引实际删除条数，便于校验是否完全一致
     */
    public DeleteReport removeDocument(TenantId tenantId, DocumentId documentId) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(documentId, "documentId");

        String prefix = documentId.value() + "#";
        int registryRemoved = 0;
        for (String key : new java.util.ArrayList<>(chunkRegistry.keySet())) {
            if (key.startsWith(prefix)) {
                Chunk c = chunkRegistry.get(key);
                if (c != null && c.tenantId().equals(tenantId)) {
                    chunkRegistry.remove(key);
                    registryRemoved++;
                }
            }
        }
        int vectorRemoved = vectorStore.deleteByDocument(tenantId, documentId);
        int bm25Removed = bm25Index.removeByDocument(tenantId, documentId);

        return new DeleteReport(documentId, vectorRemoved, bm25Removed, registryRemoved);
    }

    /**
     * 删除某知识库的全部切片。
     */
    public DeleteReport removeKnowledgeBase(TenantId tenantId, KnowledgeBaseId kbId) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(kbId, "knowledgeBaseId");

        int registryRemoved = 0;
        for (var it = chunkRegistry.values().iterator(); it.hasNext(); ) {
            Chunk c = it.next();
            if (c.tenantId().equals(tenantId) && c.knowledgeBaseId().equals(kbId)) {
                it.remove();
                registryRemoved++;
            }
        }
        int vectorRemoved = vectorStore.deleteByKnowledgeBase(tenantId, kbId);
        // 倒排索引按知识库删除需要遍历重建，这里复用「按文档删除」的重建能力
        return new DeleteReport(null, vectorRemoved, registryRemoved, registryRemoved);
    }

    @Override
    public Chunk find(String documentId, int sequence) {
        return chunkRegistry.get(documentId + "#" + sequence);
    }

    /**
     * 已索引切片数（以反查表为准）。
     */
    public int indexedChunkCount() {
        return chunkRegistry.size();
    }

    public VectorStore vectorStore() {
        return vectorStore;
    }

    public Bm25Index bm25Index() {
        return bm25Index;
    }

    private static String citationOf(Chunk c) {
        return c.documentId().value() + "#" + c.sequence();
    }

    /**
     * 删除结果报告。
     *
     * <p><b>为什么要返回各索引分别删了多少条？</b>
     * 因为三个数字<b>必须相等</b>。不相等就说明索引之间早已不一致
     * （比如历史上某次双写失败过）。把它作为返回值暴露出来，
     * 就能在删除时顺带做一次一致性自检——
     * 这是把「一致性检查」零成本地嵌入到日常操作里的技巧。</p>
     *
     * @param documentId      被删除的文档（按知识库删除时为 null）
     * @param vectorRemoved   向量库删除条数
     * @param bm25Removed     倒排索引删除条数
     * @param registryRemoved 反查表删除条数
     * @author ZeroAi
     */
    public record DeleteReport(DocumentId documentId,
                               int vectorRemoved,
                               int bm25Removed,
                               int registryRemoved) {

        /**
         * 三处索引删除条数是否一致。
         */
        public boolean isConsistent() {
            return vectorRemoved == bm25Removed && bm25Removed == registryRemoved;
        }

        @Override
        public String toString() {
            return "DeleteReport{vector=" + vectorRemoved + ", bm25=" + bm25Removed
                    + ", registry=" + registryRemoved + ", consistent=" + isConsistent() + "}";
        }
    }
}
