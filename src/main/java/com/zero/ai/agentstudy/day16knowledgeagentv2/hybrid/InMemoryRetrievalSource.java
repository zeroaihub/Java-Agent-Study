package com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/**
 * 内存版检索源（Chapter 05 演示实现）。
 *
 * <p>生产环境这里应替换为 pgvector（向量）或 Elasticsearch（BM25）适配器。为了让课程代码
 * 能独立编译运行，这里用一个可注入的「打分函数」模拟不同检索源：</p>
 * <ul>
 *     <li>向量源：用一个简单的字符重合度模拟「语义相似」；</li>
 *     <li>关键词源：用词项命中数模拟「BM25」。</li>
 * </ul>
 *
 * @author ZeroAi
 */
public class InMemoryRetrievalSource implements RetrievalSource {

    private final String name;
    private final List<RetrievedChunk> corpus;
    private final BiFunction<String, RetrievedChunk, Double> scorer;

    public InMemoryRetrievalSource(String name,
                                   List<RetrievedChunk> corpus,
                                   BiFunction<String, RetrievedChunk, Double> scorer) {
        this.name = name;
        this.corpus = List.copyOf(corpus);
        this.scorer = scorer;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<RetrievedChunk> search(AgentContext ctx, String query, int topK) {
        return corpus.stream()
                .map(c -> c.withScore(scorer.apply(query, c)))
                .filter(c -> c.score() > 0)
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 关键词打分（模拟 BM25）：查询词命中片段正文的个数。
     */
    public static double keywordScore(String query, RetrievedChunk chunk) {
        String content = chunk.content().toLowerCase();
        String[] terms = query.toLowerCase().split("\\s+");
        long hits = 0;
        for (String term : terms) {
            if (!term.isBlank() && content.contains(term)) {
                hits++;
            }
        }
        return hits;
    }

    /**
     * 语义打分（模拟向量相似度）：字符集合的 Jaccard 相似度，取值 0~1。
     */
    public static double semanticScore(String query, RetrievedChunk chunk) {
        var q = query.toLowerCase().chars().boxed().collect(java.util.stream.Collectors.toSet());
        var c = chunk.content().toLowerCase().chars().boxed().collect(java.util.stream.Collectors.toSet());
        if (q.isEmpty() || c.isEmpty()) {
            return 0;
        }
        var inter = new java.util.HashSet<>(q);
        inter.retainAll(c);
        var union = new java.util.HashSet<>(q);
        union.addAll(c);
        return (double) inter.size() / union.size();
    }
}