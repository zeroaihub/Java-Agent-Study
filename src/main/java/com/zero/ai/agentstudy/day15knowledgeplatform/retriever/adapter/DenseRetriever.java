package com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.EmbeddingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddingVector;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.SearchQuery;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port.Retriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.port.VectorStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 稠密检索器（向量检索 / Dense Retrieval）。
 *
 * <p>职责：把查询文本向量化 → 在向量库里按余弦相似度找最近邻 → 包装成带排名的结果。</p>
 *
 * <p><b>唯一需要强调的工程点：查询向量必须与库内向量来自同一个模型。</b>
 * 这不是靠"记得传同一个 bean"来保证的，而是由 {@link EmbeddingVector#requireComparable}
 * 在每次比较时强制校验。
 * <b>把规则写进类型，而不是写进文档</b>——文档会被忘记，类型不会。</p>
 *
 * <p>它的短板见 {@link com.zero.ai.agentstudy.day15knowledgeplatform.retriever.Bm25Index}
 * 的类注释：精确 token、低频专有名词、可解释性。这些短板正是需要 Hybrid 的理由。</p>
 *
 * @author ZeroAi
 */
public class DenseRetriever implements Retriever {

    public static final String NAME = "dense";

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;

    public DenseRetriever(VectorStore vectorStore, EmbeddingService embeddingService) {
        this.vectorStore = KnowledgeException.requireNonNull(vectorStore, "vectorStore");
        this.embeddingService = KnowledgeException.requireNonNull(embeddingService, "embeddingService");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<RetrievedChunk> retrieve(SearchQuery query) {
        KnowledgeException.requireNonNull(query, "query");
        EmbeddingVector queryVector;
        try {
            queryVector = embeddingService.embedText(query.queryText());
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeErrorCode.RETRIEVAL_FAILED,
                    "查询向量化失败：" + ex.getMessage(), ex);
        }

        List<VectorStore.VectorMatch> matches;
        try {
            matches = vectorStore.search(query.tenantId(), query.knowledgeBaseIds(),
                    queryVector, query.topK());
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeErrorCode.RETRIEVAL_FAILED,
                    "向量检索失败：" + ex.getMessage(), ex);
        }

        List<RetrievedChunk> out = new ArrayList<>(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            VectorStore.VectorMatch m = matches.get(i);
            int rank = i + 1; // 排名从 1 开始（RRF 的要求）
            out.add(new RetrievedChunk(m.chunk().chunk(), m.score(),
                    java.util.Map.of(NAME, rank), java.util.List.of(NAME)));
        }
        return out;
    }
}
