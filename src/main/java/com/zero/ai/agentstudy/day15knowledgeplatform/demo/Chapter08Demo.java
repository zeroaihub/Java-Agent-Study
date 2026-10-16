package com.zero.ai.agentstudy.day15knowledgeplatform.demo;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.ChunkId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.EmbeddingService;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.adapter.HashingEmbeddingModel;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.Bm25Index;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.HybridRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.KnowledgeIndexer;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.DenseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.SparseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.fusion.ReciprocalRankFusion;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.SearchQuery;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port.Retriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.MmrDiversifier;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.RerankService;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.adapter.HeuristicReranker;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.adapter.PassThroughReranker;
import com.zero.ai.agentstudy.day15knowledgeplatform.reranker.port.Reranker;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.adapter.InMemoryVectorStore;

import java.util.List;
import java.util.Map;

/**
 * 第 8 章 Demo：重排序与 MMR 多样性。
 *
 * <p>演示三件事：</p>
 * <ol>
 *     <li>精排如何把真正相关的结果从第 3 名提到第 1 名；</li>
 *     <li>MMR 如何把"重复内容占满 Top3"打散；</li>
 *     <li>基线（直通）与精排的对比——没有基线的优化都是自嗨。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class Chapter08Demo {

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("  第 8 章：knowledge-reranker —— 精排与 MMR 多样性去重");
        System.out.println("=".repeat(78));

        TenantId tenant = TenantId.of("acme");

        // ---------- 准备 ----------
        var vectorStore = new InMemoryVectorStore();
        var bm25 = new Bm25Index();
        var indexer = new KnowledgeIndexer(vectorStore, bm25);
        var embedding = new EmbeddingService(new HashingEmbeddingModel(256));
        indexer.index(embedding.embedAll(buildCorpus()));

        Retriever hybrid = new HybridRetriever(List.of(
                new DenseRetriever(vectorStore, embedding),
                new SparseRetriever(bm25, indexer)), new ReciprocalRankFusion(), true);

        SearchQuery q = new SearchQuery(tenant, "员工出差报销的发票有什么要求",
                java.util.Set.of(), 10, Metadata.empty());
        List<RetrievedChunk> recalled = hybrid.retrieve(q);

        System.out.println();
        System.out.println("召回阶段（RRF 融合后）共 " + recalled.size() + " 条候选：");
        for (int i = 0; i < recalled.size(); i++) {
            RetrievedChunk rc = recalled.get(i);
            System.out.printf("  %d. [rrf=%.4f] %-22s %s%n",
                    i + 1, rc.score(), rc.citation(), rc.chunk().preview(34));
        }

        // ---------- 1. 基线 vs 精排 ----------
        printSection("[1] 基线 vs 精排：没有基线的优化都是自嗨");

        Reranker baseline = new PassThroughReranker();
        Reranker heuristic = new HeuristicReranker();

        System.out.println("  ── 基线（直通，只截断不重排）Top3 ──");
        List<RetrievedChunk> baseTop3 = baseline.rerank(q.queryText(), recalled, 3);
        for (int i = 0; i < baseTop3.size(); i++) {
            System.out.printf("    %d. %-22s %s%n", i + 1,
                    baseTop3.get(i).citation(), baseTop3.get(i).chunk().preview(34));
        }

        System.out.println("  ── 启发式精排 Top3 ──");
        List<RetrievedChunk> reranked = heuristic.rerank(q.queryText(), recalled, 3);
        for (int i = 0; i < reranked.size(); i++) {
            System.out.printf("    %d. [%.4f] %-22s %s%n", i + 1, reranked.get(i).score(),
                    reranked.get(i).citation(), reranked.get(i).chunk().preview(34));
        }

        System.out.println();
        System.out.println("  对比两组的第 1 名：");
        System.out.println("    基线：" + baseTop3.get(0).citation() + " | " + baseTop3.get(0).chunk().preview(30));
        System.out.println("    精排：" + reranked.get(0).citation() + " | " + reranked.get(0).chunk().preview(30));
        System.out.println();
        System.out.println("  精排综合了「查询词覆盖率 + 精确短语命中 + 标题命中 + 多路命中 + 长度惩罚」，");
        System.out.println("  把「同时满足多个信号」的结果提到前面——这正是召回阶段做不到的：");
        System.out.println("  召回只看「像不像」，精排才看「到底对不对」。");

        // ---------- 2. MMR 去重 ----------
        printSection("[2] MMR 多样性：解决「Top-N 里全是同一件事」");

        System.out.println("  语料里刻意放了 3 段几乎一样的「发票要求」原文（制度版/部门版/培训版），");
        System.out.println("  它们会被同时召回并占据前几名。先看不做去重的结果：");
        System.out.println();
        System.out.println("  ── 仅精排（未去重）Top5 ──");
        List<RetrievedChunk> noMmr = new RerankService(heuristic, null, false)
                .rerank(q.queryText(), recalled, 5);
        for (int i = 0; i < noMmr.size(); i++) {
            System.out.printf("    %d. %-22s %s%n", i + 1,
                    noMmr.get(i).citation(), noMmr.get(i).chunk().preview(40));
        }

        System.out.println();
        System.out.println("  ── 精排 + MMR 去重（λ=0.7）Top5 ──");
        List<RetrievedChunk> withMmr = new RerankService(heuristic, new MmrDiversifier(0.7), true)
                .rerank(q.queryText(), recalled, 5);
        for (int i = 0; i < withMmr.size(); i++) {
            System.out.printf("    %d. %-22s %s%n", i + 1,
                    withMmr.get(i).citation(), withMmr.get(i).chunk().preview(40));
        }

        System.out.println();
        System.out.println("  MMR 公式：MMR = λ·相关性 − (1−λ)·与已选项的最大相似度");
        System.out.println("  每选一条，就惩罚掉和已选内容太像的候选。");
        System.out.println();
        System.out.println("  ★ 这一步的真实价值不是「排序更好看」，而是：");
        System.out.println("    Top-5 里少了几段重复文字，就多出几个不同的信息点。");
        System.out.println("    LLM 的上下文预算是固定的，塞满重复内容 = 挤掉真正有用的信息。");

        // ---------- 3. 降级 ----------
        printSection("[3] 优雅降级：精排服务挂了，检索不能跟着挂");

        Reranker crossEncoder = new com.zero.ai.agentstudy.day15knowledgeplatform.reranker
                .adapter.CrossEncoderReranker("http://127.0.0.1:59999/v1/rerank",
                "bge-reranker-v2-m3", heuristic);

        long start = System.nanoTime();
        List<RetrievedChunk> degraded = crossEncoder.rerank(q.queryText(), recalled, 3);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        System.out.println("  调用一个不存在的精排服务（127.0.0.1:59999）…");
        System.out.println("  耗时 " + elapsed + "ms，仍返回 " + degraded.size() + " 条结果：");
        for (RetrievedChunk rc : degraded) {
            System.out.println("    - " + rc.citation() + " | " + rc.chunk().preview(34));
        }
        System.out.println();
        System.out.println("  ★ 精排失败没有导致整个检索失败，而是自动退回了 HeuristicReranker。");
        System.out.println("    用户拿到的是「质量略降的结果」，而不是一个 500 错误页。");
        System.out.println("    当然，降级必须被监控与告警——否则它会悄悄变成永久状态。");

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  第 8 章 Demo 结束");
        System.out.println("=".repeat(78));
    }

    /**
     * 构造语料：刻意包含 3 段高度重复的「发票要求」内容。
     */
    private static List<Chunk> buildCorpus() {
        TenantId tenant = TenantId.of("acme");
        WorkspaceId ws = WorkspaceId.of("ws-finance");
        KnowledgeBaseId kb = KnowledgeBaseId.of("kb-policy");

        String[][] rows = {
                {"doc-a", "差旅报销总流程：出差返回后 30 个工作日内提交申请，经部门经理审批后交财务。"},
                // 下面三段刻意高度重复，用于演示 MMR 的价值
                {"doc-b", "发票要求（制度原文）：发票必须开具公司抬头，纳税人识别号须与实际一致，否则不予受理。"},
                {"doc-c", "发票要求（部门细则）：发票必须开具公司抬头，纳税人识别号须与实际一致，否则不予受理。"},
                {"doc-d", "发票要求（培训材料）：发票必须开具公司抬头，纳税人识别号须与实际一致，否则不予受理。"},
                {"doc-e", "审批权限：单笔 5000 元以下由部门经理审批，以上需财务总监双签。"},
                {"doc-f", "发票遗失处理：如发票遗失，需提供开票方证明与付款凭证，经财务总监特批。"},
                {"doc-g", "员工出差回来怎么报销？先在系统里填单，再贴发票，最后交给财务审核。"},
                {"doc-h", "境外发票：境外取得的票据需附翻译件与汇率折算说明。"},
        };

        List<Chunk> out = new java.util.ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            String text = rows[i][1];
            out.add(new Chunk(ChunkId.newId(), tenant, ws, kb, DocumentId.of(rows[i][0]),
                    i, text, text.length(), text.length() / 2,
                    Metadata.of(Map.of("documentTitle", "发票与报销制度"))));
        }
        return out;
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("-".repeat(78));
        System.out.println(title);
        System.out.println("-".repeat(78));
    }
}
