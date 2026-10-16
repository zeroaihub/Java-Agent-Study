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
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.HybridRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.Bm25Index;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.KnowledgeIndexer;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.RetrievalService;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.DenseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.adapter.SparseRetriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.fusion.ReciprocalRankFusion;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.RetrievedChunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.model.SearchQuery;
import com.zero.ai.agentstudy.day15knowledgeplatform.retriever.port.Retriever;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.adapter.InMemoryVectorStore;
import com.zero.ai.agentstudy.day15knowledgeplatform.vectorstore.port.VectorStore;

import java.util.List;
import java.util.Map;

/**
 * 第 7 章 Demo：Hybrid Search —— 向量 + BM25 + RRF 融合。
 *
 * <p>本章是全 Day15 的关键验证：<b>用真实数据证明「为什么要两路检索」</b>。
 * 我们构造一个刻意设计的数据集，让向量与 BM25 各自会漏掉一部分，
 * 再看融合后是否补齐。</p>
 *
 * @author ZeroAi
 */
public class Chapter07Demo {

    public static void main(String[] args) {
        System.out.println("=".repeat(78));
        System.out.println("  第 7 章：knowledge-retriever —— Hybrid Search 与 RRF 融合");
        System.out.println("=".repeat(78));

        // ---------- 准备：建索引 ----------
        VectorStore vectorStore = new InMemoryVectorStore();
        Bm25Index bm25 = new Bm25Index();
        KnowledgeIndexer indexer = new KnowledgeIndexer(vectorStore, bm25);
        EmbeddingService embedding = new EmbeddingService(new HashingEmbeddingModel(256));

        List<Chunk> corpus = buildCorpus();
        List<EmbeddedChunk> embedded = embedding.embedAll(corpus);
        indexer.index(embedded);
        System.out.println();
        System.out.println("已索引 " + indexer.indexedChunkCount() + " 个切片");

        Retriever dense = new DenseRetriever(vectorStore, embedding);
        Retriever sparse = new SparseRetriever(bm25, indexer);
        HybridRetriever hybrid = new HybridRetriever(List.of(dense, sparse),
                new ReciprocalRankFusion(), true);

        TenantId tenant = TenantId.of("acme");

        // ---------- 1. 精确 token ----------
        query("[1] 精确 token 检索：错误码 KP-4001（BM25 的强项）",
                dense, sparse, hybrid, tenant, "KP-4001 是什么错误");

        // ---------- 2. 换个说法 ----------
        query("[2] 换个说法提问：口语化查询（稠密检索的强项）",
                dense, sparse, hybrid, tenant, "员工出差回来怎么把钱报了");

        // ---------- 3. 融合的价值 ----------
        printSection("[3] RRF 融合的价值：为什么「两路都命中」比「一路第一名」更可信");
        System.out.println("  RRF 公式：score(d) = Σ 1 / (60 + rank_i(d))");
        System.out.println();
        System.out.println("  对比两种情况的融合分：");
        System.out.printf("    A. 向量第 1 名、关键词第 50 名：%.4f%n", 1.0 / 61 + 1.0 / 110);
        System.out.printf("    B. 向量第 5 名、关键词第 5 名：%.4f%n", 2.0 / 65);
        System.out.println();
        System.out.println("  → B > A。这就是 k=60 的作用：压低单路高排名的垄断性，");
        System.out.println("    让「两路都认可」的结果排到「一路独秀」之前。");
        System.out.println("    直觉上也很合理：两种完全不同的算法都认为相关，大概率真相关。");

        // ---------- 4. 租户隔离 ----------
        printSection("[4] 租户隔离：另一家公司的相同查询，什么都搜不到");
        TenantId other = TenantId.of("globex");
        RetrievalService svc = new RetrievalService(hybrid);
        var report = svc.search(new SearchQuery(other, "差旅报销", java.util.Set.of(), 5, Metadata.empty()));
        System.out.println("  以租户 globex 检索：" + report);
        System.out.println("  → 命中 " + report.results().size() + " 条（应为 0）");
        System.out.println("  过滤发生在存储层与倒排索引层，无权数据根本不会进入内存。");

        // ---------- 5. 作用域下推 + 零命中的差异 ----------
        printSection("[5] 知识库作用域下推 —— 以及一个容易被忽视的真相：向量检索没有「零命中」");

        System.out.println("  差旅报销内容在 kb-policy；现在故意限定在 kb-product 里搜「报销」：");
        System.out.println();

        SearchQuery wrongScope = new SearchQuery(tenant, "报销",
                java.util.Set.of(KnowledgeBaseId.of("kb-product")), 3, Metadata.empty());

        System.out.println("  ── 关键词（BM25）──");
        List<RetrievedChunk> sWrong = sparse.retrieve(wrongScope);
        if (sWrong.isEmpty()) {
            System.out.println("    （无命中）← 正确！该知识库里没有一个字与「报销」匹配");
        } else {
            for (RetrievedChunk rc : sWrong) {
                System.out.println("    - " + rc.citation());
            }
        }

        System.out.println("  ── 向量（dense）──");
        List<RetrievedChunk> dWrong = dense.retrieve(wrongScope);
        for (RetrievedChunk rc : dWrong) {
            System.out.printf("    %-28s score=%.4f  %s%n",
                    rc.citation(), rc.score(), rc.chunk().preview(24));
        }
        System.out.println("    命中了 " + dWrong.size() + " 条，但它们与「报销」毫无关系！");
        System.out.println();
        System.out.println("  ★ 这是向量检索一个极容易被忽视的特性：");
        System.out.println("    「最近邻」永远是存在的——哪怕库里全是无关内容，");
        System.out.println("    它也一定会返回余弦相似度最高的那几条。");
        System.out.println("    而 BM25 是稀疏检索，一个词都没命中就是 0 条。");
        System.out.println();
        System.out.println("  ★ 生产环境必须由此引出一个必备配置：最小相似度阈值（minScore）。");
        System.out.println("    低于阈值的命中直接丢弃，否则「问什么都返回一堆不相关内容」，");
        System.out.println("    这些噪声被塞进 Prompt 后，LLM 会一本正经地胡说八道。");
        System.out.println("    阈值需要按业务标定：一般 0.7~0.75 是个起点。");

        System.out.println();
        System.out.println("  作用域切回正确的 kb-policy：");
        var scoped2 = svc.search(new SearchQuery(tenant, "报销",
                java.util.Set.of(KnowledgeBaseId.of("kb-policy")), 3, Metadata.empty()));
        for (RetrievedChunk rc : scoped2.results()) {
            System.out.println("    - " + rc.citation() + " | " + rc.chunk().preview(30));
        }

        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  第 7 章 Demo 结束");
        System.out.println("=".repeat(78));
    }

    private static void query(String title, Retriever dense, Retriever sparse,
                              Retriever hybrid, TenantId tenant, String queryText) {
        printSection(title);
        SearchQuery q = new SearchQuery(tenant, queryText, java.util.Set.of(), 3, Metadata.empty());

        List<RetrievedChunk> d = dense.retrieve(q);
        List<RetrievedChunk> s = sparse.retrieve(q);
        List<RetrievedChunk> h = hybrid.retrieve(q);

        System.out.println("  查询：" + queryText);
        System.out.println();
        System.out.println("  ── 仅向量（dense）──");
        for (RetrievedChunk rc : d) {
            System.out.printf("    %-28s score=%.4f%n", rc.citation(), rc.score());
        }
        if (d.isEmpty()) {
            System.out.println("    （无命中）");
        }
        System.out.println("  ── 仅关键词（sparse / BM25）──");
        for (RetrievedChunk rc : s) {
            System.out.printf("    %-28s score=%.4f%n", rc.citation(), rc.score());
        }
        if (s.isEmpty()) {
            System.out.println("    （无命中）");
        }
        System.out.println("  ── 混合（RRF 融合后 Top3）──");
        for (RetrievedChunk rc : h.subList(0, Math.min(3, h.size()))) {
            System.out.printf("    %-28s rrf=%.4f  ranks=%s  %s%n",
                    rc.citation(), rc.score(), rc.ranks(),
                    rc.isHitByAll() ? "★两路都命中" : "单路命中");
        }
        System.out.println();
        System.out.println("  【诚实的说明】本 Demo 用的是 HashingEmbeddingModel（字面相似），");
        System.out.println("  并非真正的语义模型，所以两路结果会比较接近。");
        System.out.println("  换成 bge / nomic / text-embedding-3 之后，两者的差异会明显拉开：");
        System.out.println("    精确 token（错误码、型号、方法名）→ BM25 明显更强；");
        System.out.println("    口语化改写、同义替换 → 稠密检索明显更强。");
        System.out.println("  但无论模型强弱，融合都成立：两路都认可的，最可信。");
    }

    /**
     * 构造语料。
     *
     * <p>刻意设计成「向量与 BM25 各有盲区」：</p>
     * <ul>
     *     <li>{@code KP-4001} 这类精确 token，字面匹配强、语义泛化弱；</li>
     *     <li>「怎么把钱报了」这类口语化改写，字面几乎不重合。</li>
     * </ul>
     */
    private static List<Chunk> buildCorpus() {
        TenantId tenant = TenantId.of("acme");
        WorkspaceId ws = WorkspaceId.of("ws-finance");

        String[][] policy = {
                {"doc-policy-01", "kb-policy", "差旅费用报销标准：员工出差返回后 30 个工作日内提交申请，需附发票原件与行程单。"},
                {"doc-policy-02", "kb-policy", "报销审批权限：单笔 5000 元以下由部门经理审批，以上需财务总监双签。"},
                {"doc-policy-03", "kb-policy", "员工出差回来怎么报销？先在系统里填单，再贴发票，最后交给财务审核。"},
                {"doc-policy-04", "kb-policy", "发票开具要求：公司抬头、纳税人识别号必须与实际一致，否则不予受理。"},
        };
        String[][] product = {
                {"doc-prod-01", "kb-product", "错误码说明：KP-4001 表示向量化失败，通常由模型不可用或文本超长引起。"},
                {"doc-prod-02", "kb-product", "错误码说明：KP-5001 表示检索失败，请检查向量库连接与索引状态。"},
                {"doc-prod-03", "kb-product", "系统架构：知识平台由解析、切分、向量化、检索四个模块组成。"},
        };

        List<Chunk> out = new java.util.ArrayList<>();
        int seq = 0;
        for (String[] row : policy) {
            out.add(chunk(tenant, ws, KnowledgeBaseId.of(row[1]), DocumentId.of(row[0]), seq++, row[2]));
        }
        for (String[] row : product) {
            out.add(chunk(tenant, ws, KnowledgeBaseId.of(row[1]), DocumentId.of(row[0]), seq++, row[2]));
        }
        return out;
    }

    private static Chunk chunk(TenantId t, WorkspaceId w, KnowledgeBaseId kb, DocumentId doc,
                               int seq, String text) {
        return new Chunk(ChunkId.newId(), t, w, kb, doc, seq, text,
                text.length(), text.length() / 2,
                Metadata.of(Map.of("documentTitle", "企业制度与产品文档")));
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println("-".repeat(78));
        System.out.println(title);
        System.out.println("-".repeat(78));
    }
}
