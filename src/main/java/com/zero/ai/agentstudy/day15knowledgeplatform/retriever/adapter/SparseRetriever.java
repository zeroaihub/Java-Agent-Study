package com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.Bm25Index;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.SearchQuery;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port.Retriever;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 稀疏检索器（关键词检索 / BM25）。
 *
 * <p>与 {@link DenseRetriever} 的差别不只是算法，还有<b>返回结果的形态</b>：
 * BM25 是稀疏检索，<b>一个查询词都没命中的切片，根本不会出现在结果里</b>。
 * 这不是缺陷，而是特性——它意味着「零命中」是常态，融合时必须接受这一点。</p>
 *
 * <p><b>一个必须说清楚的工程细节</b>：本类需要把 BM25 的内部命中（只有 docId）
 * 还原成完整的 {@link Chunk}。这里通过 {@code ChunkLookup} 回调注入，
 * 而不是让 {@link Bm25Index} 直接持有 Chunk 列表。</p>
 *
 * <p>为什么这么做？<b>为了保持索引的纯粹性。</b>
 * 倒排索引只关心「词 → 文档」的映射关系，它不应该知道 Chunk 长什么样、
 * 有多少字段。让索引持有业务对象，会导致索引与领域模型耦合，
 * 一旦 Chunk 加字段，索引就得跟着改。用回调解耦后，
 * 索引可以独立演进（换成 Lucene、换成 Elasticsearch），业务侧无感。</p>
 *
 * @author ZeroAi
 */
public class SparseRetriever implements Retriever {

    public static final String NAME = "sparse";

    /**
     * 切片查找回调：按「文档 ID + 序号」还原完整 Chunk。
     */
    @FunctionalInterface
    public interface ChunkLookup {
        Chunk find(String documentId, int sequence);
    }

    private final Bm25Index index;
    private final ChunkLookup lookup;

    public SparseRetriever(Bm25Index index, ChunkLookup lookup) {
        this.index = KnowledgeException.requireNonNull(index, "bm25Index");
        this.lookup = KnowledgeException.requireNonNull(lookup, "chunkLookup");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievedChunk> retrieve(SearchQuery query) {
        KnowledgeException.requireNonNull(query, "query");
        List<Bm25Index.Match> matches =
                index.search(query.tenantId(), query.knowledgeBaseIds(), query.queryText(), query.topK());

        List<RetrievedChunk> out = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            Bm25Index.Match m = matches.get(i);
            Chunk chunk = lookup.find(m.entry().documentId().value(), sequenceOf(m.entry().chunkId()));
            if (chunk == null) {
                // 索引与切片存储不一致（如删除同步尚未完成），跳过而非崩溃。
                // 这里必须「静默跳过」而不是抛异常：检索服务不该因为个别脏数据整体不可用。
                continue;
            }
            int rank = i + 1;
            out.add(new RetrievedChunk(chunk, m.score(),
                    Map.of(NAME, rank), List.of(NAME)));
        }
        return out;
    }

    /**
     * 从内部 chunkId（形如 {@code doc-xxx#3}）解析出序号。
     *
     * <p>用字符串拼接 ID 再解析回来，看起来有点笨。
     * 这里刻意保留这个笨拙，是为了暴露一个真实的教训：
     * <b>跨层传递标识时，尽量用强类型值对象而不是拼接字符串</b>。
     * 一旦拼了字符串，格式约定就散落在两处（写入时拼、读取时拆），
     * 任何一处改动都会静默产生 null。生产实现应让 Entry 直接持有 {@code ChunkId}。</p>
     */
    private static int sequenceOf(String internalChunkId) {
        int idx = internalChunkId.lastIndexOf('#');
        if (idx < 0 || idx == internalChunkId.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(internalChunkId.substring(idx + 1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
