package com.zero.ai.agentstudy.day05rag.service;

import com.zero.ai.agentstudy.day05rag.embedding.EmbeddingClient;
import com.zero.ai.agentstudy.day05rag.embedding.HashEmbeddingClient;
import com.zero.ai.agentstudy.day05rag.entity.Chunk;
import com.zero.ai.agentstudy.day05rag.entity.SearchResult;
import com.zero.ai.agentstudy.day05rag.llm.EchoLlmClient;
import com.zero.ai.agentstudy.day05rag.llm.LlmClient;
import com.zero.ai.agentstudy.day05rag.prompt.PromptBuilder;
import com.zero.ai.agentstudy.day05rag.retriever.Retriever;
import com.zero.ai.agentstudy.day05rag.splitter.TextSplitter;
import com.zero.ai.agentstudy.day05rag.vectorstore.InMemoryVectorStore;
import com.zero.ai.agentstudy.day05rag.vectorstore.VectorStore;

import java.util.List;

/**
 * RagService —— RAG 系统的「总指挥」，把前七章的零件焊成一台完整机器。
 *
 * <p>它自己不写算法，只负责「编排流程」，对外暴露两个动作：</p>
 * <ul>
 *   <li>{@link #index(String, String)} —— 离线索引流：切分 → 向量化 → 入库</li>
 *   <li>{@link #ask(String)} —— 在线问答流：召回 → 拼 Prompt → LLM 生成</li>
 * </ul>
 *
 * <p>依赖倒置：6 个协作者全部通过构造器注入（面向接口），实现可随意替换
 * （HashEmbedding→云端模型、InMemory→pgvector、Echo→真 LLM），本类无需改动。</p>
 *
 * <p><b>铁律①</b>：{@link #index} 入库与 {@link #ask} 查询必须用同一 EmbeddingClient，
 * 本类通过「构造时注入同一实例 + Retriever 也复用它」来保证。</p>
 *
 * @author ZeroAi
 */
public class RagService {

    /** 文本切分：长文档 → 多个小块 */
    private final TextSplitter splitter;

    /** 文本向量化：与 Retriever 内部必须是同一实例（铁律①） */
    private final EmbeddingClient embeddingClient;

    /** 向量库：索引流写入、问答流读取 */
    private final VectorStore vectorStore;

    /** 召回器：问题 → 相关片段 */
    private final Retriever retriever;

    /** Prompt 组装器：片段 + 问题 → 结构化提示词 */
    private final PromptBuilder promptBuilder;

    /** 大模型客户端：Prompt → 答案 */
    private final LlmClient llmClient;

    /** 自增序号，用于给入库片段生成唯一 id */
    private int autoId = 0;

    /**
     * 构造：注入全部 6 个协作者（依赖倒置，便于替换与测试）。
     *
     * @param splitter        文本切分器
     * @param embeddingClient 文本向量化（须与 retriever 内部同一实例）
     * @param vectorStore     向量库
     * @param retriever       召回器
     * @param promptBuilder   Prompt 组装器
     * @param llmClient       大模型客户端
     */
    public RagService(TextSplitter splitter,
                      EmbeddingClient embeddingClient,
                      VectorStore vectorStore,
                      Retriever retriever,
                      PromptBuilder promptBuilder,
                      LlmClient llmClient) {
        if (splitter == null || embeddingClient == null || vectorStore == null
                || retriever == null || promptBuilder == null || llmClient == null) {
            throw new IllegalArgumentException("RagService 的 6 个协作者均不能为空");
        }
        this.splitter = splitter;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
    }

    /**
     * 离线索引流：把一篇文档灌进知识库。
     *
     * <p>三步：① 切分成小块 ② 每块向量化 ③ 组装 Chunk 存入向量库（带来源元数据）。</p>
     *
     * @param document 文档全文
     * @param source   来源标识（如「员工手册.pdf」），用于答案溯源
     * @return 本次入库的片段数量
     */
    public int index(String document, String source) {
        if (document == null || document.trim().isEmpty()) {
            return 0;
        }
        // ① 切分
        List<String> pieces = splitter.split(document);
        int count = 0;
        for (String piece : pieces) {
            // ② 向量化（铁律①：与查询同一模型）
            float[] vector = embeddingClient.embed(piece);
            // ③ 组装 Chunk 并入库，附带来源便于溯源
            Chunk chunk = new Chunk("chunk-" + (autoId++), piece, vector)
                    .addMetadata("source", source);
            vectorStore.save(chunk);
            count++;
        }
        return count;
    }

    /**
     * 在线问答流：问一句，返回一段基于知识库的答案。
     *
     * <p>三步：① 召回相关片段 ② 拼结构化 Prompt ③ 交给 LLM 生成。
     * 召回为空时，PromptBuilder 会拼「（无相关资料）」，LLM 据此回答「未找到」。</p>
     *
     * @param question 用户问题
     * @return 大模型生成的答案
     */
    public String ask(String question) {
        // ① 召回
        List<SearchResult> results = retriever.retrieve(question);
        // ② 组装 Prompt
        String prompt = promptBuilder.build(question, results);
        // ③ 生成
        return llmClient.chat(prompt);
    }

    /**
     * 演示入口：端到端跑通一次完整 RAG 流程。
     *
     * <p>组装同一 embeddingClient 给 Retriever 和 RagService（保证铁律①），
     * 先 index 一段 HR 政策，再问两个问题：一个知识库里有、一个没有。</p>
     */
    public static void main(String[] args) {
        // ---- 组装：注意 embedding 只 new 一次，Retriever 与 RagService 共享 ----
        EmbeddingClient embedding = new HashEmbeddingClient();
        VectorStore store = new InMemoryVectorStore();
        TextSplitter splitter = new TextSplitter(30, 5);
        Retriever retriever = new Retriever(embedding, store); // 复用同一 embedding
        PromptBuilder promptBuilder = new PromptBuilder();
        LlmClient llm = new EchoLlmClient();

        RagService rag = new RagService(
                splitter, embedding, store, retriever, promptBuilder, llm);

        // ---- 离线索引 ----
        System.out.println("=== 索引 ===");
        String doc = "员工每年享有10天带薪年假。"
                + "差旅报销需在30天内提交发票。"
                + "试用期为3个月，转正后年假增加。"
                + "公司提供五险一金及补充医疗保险。";
        int n = rag.index(doc, "员工手册.pdf");
        System.out.println("本次入库片段数: " + n + "，向量库共 " + store.size() + " 条");

        // ---- 在线问答（知识库里有） ----
        String q1 = "员工每年有多少天年假";
        System.out.println("\n=== 提问：" + q1 + " ===");
        System.out.println("[答案] " + rag.ask(q1));

        // ---- 在线问答（知识库里没有 → 兜底） ----
        String q2 = "上班可以带宠物吗";
        System.out.println("\n=== 提问：" + q2 + "（知识库没有）===");
        System.out.println("[答案] " + rag.ask(q2));
    }
}