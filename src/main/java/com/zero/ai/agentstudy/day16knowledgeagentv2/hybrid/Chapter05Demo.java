package com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.List;
import java.util.Set;

/**
 * Chapter 05 演示：混合检索（向量 + 关键词 + RRF 融合）。
 *
 * <p>运行可见：单独用关键词或向量各有偏差，RRF 融合后综合排序更稳健。</p>
 *
 * @author ZeroAi
 */
public class Chapter05Demo {

    public static void main(String[] args) {
        List<RetrievedChunk> corpus = List.of(
                new RetrievedChunk("c1", "d1", "员工手册", "公司年假政策：每年 10 天带薪年假", 0, Set.of("EMPLOYEE")),
                new RetrievedChunk("c2", "d1", "员工手册", "病假需提供医院证明，年假与病假分开计算", 0, Set.of("EMPLOYEE")),
                new RetrievedChunk("c3", "d2", "报销制度", "差旅报销需在返岗后 15 天内提交", 0, Set.of("EMPLOYEE")),
                new RetrievedChunk("c4", "d3", "薪酬制度", "年终奖发放与年假天数无关", 0, Set.of("HR"))
        );

        RetrievalSource vector = new InMemoryRetrievalSource(
                "vector", corpus, InMemoryRetrievalSource::semanticScore);
        RetrievalSource keyword = new InMemoryRetrievalSource(
                "keyword", corpus, InMemoryRetrievalSource::keywordScore);

        HybridRetriever hybrid = new HybridRetriever(List.of(vector, keyword));

        AgentContext ctx = AgentContext.of(
                TenantId.of("tenant-01"),
                WorkspaceId.of("ws-01"),
                UserId.of("alice"),
                Set.of("EMPLOYEE"));

        String query = "年假 多少 天";

        System.out.println("=== 单独关键词检索 ===");
        keyword.search(ctx, query, 5).forEach(Chapter05Demo::print);

        System.out.println("\n=== 单独向量检索 ===");
        vector.search(ctx, query, 5).forEach(Chapter05Demo::print);

        System.out.println("\n=== 混合检索（RRF 融合）===");
        hybrid.retrieve(ctx, query, 3).forEach(Chapter05Demo::print);
    }

    private static void print(RetrievedChunk c) {
        System.out.printf("  [%.4f] %s | %s%n", c.score(), c.chunkId(), c.content());
    }
}