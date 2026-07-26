package com.zero.ai.agentstudy.day05rag.mycode.retriever;

import com.zero.ai.agentstudy.day05rag.mycode.embedding.EmbeddingClient;
import com.zero.ai.agentstudy.day05rag.mycode.vectorstore.InMemoryVectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DocumentRetriever：召回器。给一个问题，找回最相关的 Top-K 资料块。
 *
 * <p>为什么单独抽一层？
 * 「召回」是 RAG 里最需要独立优化的一环（Rerank、Hybrid Search、阈值过滤都加在这里）。
 * 把它独立成一层，上层 Service 只说「给我召回资料」，不关心内部怎么召。
 * 以后要加二次精排、加关键词融合，只改这个类。
 *
 * <p>职责：把「问题文本」变成向量（调 EmbeddingClient），
 * 再去向量库做相似检索（调 VectorStore），返回命中结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentRetriever {


    private final EmbeddingClient embeddingClient;
    private final InMemoryVectorStore vectorStore;

    /**
     * 根据问题召回 Top-K 相关块。
     *
     * @param question 用户问题
     * @param topK     取前几条
     * @return 命中结果（含相似度分数），已按分数降序
     */
    public List<InMemoryVectorStore.Hit> retrieve(String question, int topK) {
        // 1. 问题 → 向量（必须与文档用同一个 Embedding 模型，向量空间才一致）
        float[] queryVector = embeddingClient.embed(question);

        // 2. 向量库相似检索
        List<InMemoryVectorStore.Hit> hits = vectorStore.search(queryVector, topK);

        log.info("[Retriever] 问题=\"{}\" 召回 {} 条，最高分={}",
                question, hits.size(),
                hits.isEmpty() ? "-" : String.format("%.4f", hits.get(0).score()));
        return hits;
    }
}