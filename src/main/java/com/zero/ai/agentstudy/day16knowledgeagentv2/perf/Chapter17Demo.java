package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.InMemoryRetrievalSource;
import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.RetrievalSource;
import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.RetrievedChunk;

import java.util.List;
import java.util.Set;

/**
 * Chapter 17 演示：并行检索 + 租户感知缓存。
 *
 * <p>先用虚拟线程并发查询「向量源 + 关键词源」，再演示缓存命中率。</p>
 *
 * @author ZeroAi
 */
public class Chapter17Demo {

    public static void main(String[] args) {
        AgentContext ctx = AgentContext.of(
                TenantId.of("acme"),
                WorkspaceId.of("ws-1"),
                UserId.of("u-1001"),
                Set.of("EMPLOYEE"));

        List<RetrievedChunk> corpus = List.of(
                new RetrievedChunk("c1", "d1", "员工手册", "公司年假政策每年 10 天", 0, Set.of()),
                new RetrievedChunk("c2", "d2", "报销制度", "差旅报销需在 30 天内提交", 0, Set.of()));

        RetrievalSource vector = new InMemoryRetrievalSource(
                "vector", corpus, InMemoryRetrievalSource::semanticScore);
        RetrievalSource keyword = new InMemoryRetrievalSource(
                "keyword", corpus, InMemoryRetrievalSource::keywordScore);

        // === 并行检索 ===
        ParallelRetriever retriever = new ParallelRetriever(List.of(vector, keyword));
        List<List<RetrievedChunk>> results = retriever.retrieveAll(ctx, "年假 政策", 5);
        System.out.println("=== 并行检索 ===");
        System.out.println("  向量源命中: " + results.get(0).size()
                + " | 关键词源命中: " + results.get(1).size());

        // === 租户感知缓存 ===
        System.out.println("\n=== 租户缓存 ===");
        TenantAwareCache<String> cache = new TenantAwareCache<>();
        for (int i = 0; i < 3; i++) {
            String v = cache.get(ctx, "q:年假政策", () -> {
                System.out.println("  [miss] 真正执行检索…");
                return "公司每年 10 天年假";
            });
            System.out.println("  第 " + (i + 1) + " 次返回: " + v);
        }
        System.out.printf("  命中率: %.0f%%（hits=%d, misses=%d）%n",
                cache.hitRate() * 100, cache.hits(), cache.misses());
    }
}