package com.zero.ai.agentstudy.day05rag.retriever;

import com.zero.ai.agentstudy.day05rag.embedding.EmbeddingClient;
import com.zero.ai.agentstudy.day05rag.embedding.HashEmbeddingClient;
import com.zero.ai.agentstudy.day05rag.entity.Chunk;
import com.zero.ai.agentstudy.day05rag.entity.SearchResult;
import com.zero.ai.agentstudy.day05rag.vectorstore.InMemoryVectorStore;
import com.zero.ai.agentstudy.day05rag.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Retriever —— 召回总指挥：把「问题文本」变成「相关片段列表」。
 *
 * <p>它是「离线索引」与「在线问答」的交汇点，内部三步：</p>
 * <ol>
 *   <li>用 {@link EmbeddingClient} 把问题文本向量化（铁律①：与入库同一模型）</li>
 *   <li>调 {@link VectorStore#search} 按相似度检索 Top-K</li>
 *   <li>用相似度阈值过滤掉不相关结果（防 LLM 瞎编）</li>
 * </ol>
 *
 * <p>面向接口：依赖 EmbeddingClient / VectorStore 两个接口，实现可随意替换
 * （离线降级 → 云端模型、内存库 → pgvector），Retriever 本身不用改。</p>
 *
 * @author ZeroAi
 */
public class Retriever {

    /** 文本→向量：必须与入库时同一个实例/同一模型（铁律①） */
    private final EmbeddingClient embeddingClient;

    /** 向量库：负责按向量检索 Top-K */
    private final VectorStore vectorStore;

    /** 默认召回条数：3~5 起步 */
    private static final int DEFAULT_TOP_K = 3;

    /** 默认相似度阈值：低于它视为不相关，过滤掉 */
    private static final double DEFAULT_THRESHOLD = 0.5;

    /**
     * 构造：注入 Embedding 客户端与向量库（依赖倒置，便于替换与测试）。
     *
     * @param embeddingClient 文本向量化能力
     * @param vectorStore     向量存储与检索能力
     */
    public Retriever(EmbeddingClient embeddingClient, VectorStore vectorStore) {
        if (embeddingClient == null || vectorStore == null) {
            throw new IllegalArgumentException("embeddingClient 与 vectorStore 不能为空");
        }
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    /**
     * 用默认 topK 与阈值检索。
     *
     * @param question 用户问题
     * @return 相关片段列表（已按阈值过滤，可能为空）
     */
    public List<SearchResult> retrieve(String question) {
        return retrieve(question, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    /**
     * 核心召回：向量化 → 检索 → 阈值过滤。
     *
     * @param question  用户问题
     * @param topK      召条数
     * @param threshold 相似度阈值，仅保留 score >= threshold 的结果
     * @return 相关片段列表（可能为空，表示知识库中没有相关信息）
     */
    public List<SearchResult> retrieve(String question, int topK, double threshold) {
        if (question == null || question.trim().isEmpty()) {
            return new ArrayList<>();
        }
        // 1. 问题向量化（与入库同一模型）
        float[] queryVector = embeddingClient.embed(question);
        // 2. 向量库检索 Top-K
        List<SearchResult> candidates = vectorStore.search(queryVector, topK);
        // 3. 阈值过滤：去掉不相关的，防止 LLM 基于无关内容瞎编
        return candidates.stream()
                .filter(r -> r.getScore() >= threshold)
                .collect(Collectors.toList());
    }

    /**
     * 演示入口：入库几条 HR 政策片段，用一个问题检索，观察不同阈值下的召回。
     *
     * <p>注意：这里用离线 HashEmbeddingClient，它不懂语义，仅演示链路可跑通；
     * 真实语义召回需换成云端/本地模型实现。</p>
     */
    public static void main(String[] args) {
        // 组装：线 Embedding + 内存向量库（入库与查询共享同一 embeddingClient）
        EmbeddingClient embeddingClient = new HashEmbeddingClient();
        VectorStore vectorStore = new InMemoryVectorStore();

        // ---- 离线索引：把几条原文向量化后入库 ----
        String[] docs = {
                "员工每年享有10天带薪年假",
                "差旅报销需在30天内提交发票",
                "试用期为3个月，转正后年假增加",
                "公司提供五险一金及补充医疗保险"
        };
        for (int i = 0; i < docs.length; i++) {
            String text = docs[i];
            float[] vec = embeddingClient.embed(text); // 铁律①：入库用同一模型
            vectorStore.save(new Chunk("doc-" + i, text, vec)
                    .addMetadata("source", "员工手册.pdf"));
        }
        System.out.println("已入库记录数: " + vectorStore.size());

        // ---- 在线检索 ----
        Retriever retriever = new Retriever(embeddingClient, vectorStore);
        String question = "员工每年有多少天年假";

        System.out.println("\n问题: " + question);

        // 阈值 0.0：不过滤，看全部 Top-3 及其得分
        System.out.println("\n[阈值=0.0，看全部候选] ");
        List<SearchResult> all = retriever.retrieve(question, 3, 0.0);
        printResults(all);

        // 阈值 0.5：过滤不相关
        System.out.println("\n[阈值=0.5，过滤不相关] ");
        List<SearchResult> filtered = retriever.retrieve(question, 3, 0.5);
        printResults(filtered);

        // 一个知识库里根本没有的问题
        String noHit = "上班可以带宠物吗";
        System.out.println("\n问题: " + noHit + "  [阈值=0.6] ");
        List<SearchResult> none = retriever.retrieve(noHit, 3, 0.6);
        if (none.isEmpty()) {
            System.out.println("  （召回为空 → 上层应回答：知识库中没有相关信息）");
        } else {
            printResults(none);
        }
    }

    /** 打印召回结果小工具 */
    private static void printResults(List<SearchResult> results) {
        if (results.isEmpty()) {
            System.out.println("  （空）");
            return;
        }
        for (SearchResult r : results) {
            System.out.println("  " + r);
        }
    }
}