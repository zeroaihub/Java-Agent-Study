package com.zero.ai.agentstudy.day05rag.mycode.service;

import com.zero.ai.agentstudy.day05rag.mycode.dto.AskResponse;
import com.zero.ai.agentstudy.day05rag.mycode.dto.IngestResponse;
import com.zero.ai.agentstudy.day05rag.mycode.embedding.EmbeddingClient;
import com.zero.ai.agentstudy.day05rag.mycode.entity.Chunk;
import com.zero.ai.agentstudy.day05rag.mycode.entity.EmbeddedChunk;
import com.zero.ai.agentstudy.day05rag.mycode.retriever.DocumentRetriever;
import com.zero.ai.agentstudy.day05rag.mycode.splitter.TextSplitter;
import com.zero.ai.agentstudy.day05rag.mycode.vectorstore.InMemoryVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RagService：RAG 的「总指挥」，编排两条主线。
 *
 * <p>① 离线索引流 {@link #ingest}：文档 → 切分 → 向量化 → 存储。
 * <p>② 在线问答流 {@link #ask}：问题 → 召回 → 拼 Prompt → LLM 生成 → 返回答案 + 出处。
 *
 * <p>本类不自己做具体的切分/向量化/检索，而是「调用各专职组件」，
 * 这样每一步都能独立替换、独立测试 —— 这就是企业分层的意义。
 */
@Slf4j
@Service
public class RagService {

    private final TextSplitter splitter;
    private final EmbeddingClient embeddingClient;
    private final InMemoryVectorStore vectorStore;
    private final DocumentRetriever retriever;
    private final ChatClient chatClient;

    /** 召回默认取 Top-3 */
    private static final int DEFAULT_TOP_K = 3;

    /** 相似度阈值：低于此值认为不相关，避免拿无关资料误导 LLM。
     *  中文短问题 vs 长文档块相似度普遍偏低，阈值不宜过高，否则会误杀正确命中。 */
    private static final double SCORE_THRESHOLD = 0.2;

    public RagService(TextSplitter splitter,
                      EmbeddingClient embeddingClient,
                      InMemoryVectorStore vectorStore,
                      DocumentRetriever retriever,
                      ChatModel chatModel) {
        this.splitter = splitter;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.retriever = retriever;
        // 复用 Spring AI 自动装配的 ChatModel（即 LM Studio 聊天模型）构建 ChatClient
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 离线索引流：把一篇文档灌入知识库。
     */
    public IngestResponse ingest(String title, String content) {
        // 1. 切分：长文 → 多个小 Chunk
        List<Chunk> chunks = splitter.splitBySpringAi(title, content);
        if (chunks.isEmpty()) {
            return new IngestResponse(title, 0, vectorStore.size());
        }

        // 2. 向量化：一次性批量把所有 Chunk 文本转成向量（省 HTTP 往返
        List<String> texts = chunks.stream().map(Chunk::getContent).toList();
        List<float[]> vectors = embeddingClient.embed(texts);

        // 3. 组装 EmbeddedChunk（原文 + 向量）并存入向量库
        List<EmbeddedChunk> embedded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            embedded.add(new EmbeddedChunk(chunks.get(i), vectors.get(i)));
        }
        vectorStore.saveAll(embedded);

        log.info("[RAG-Ingest] 文档《{}》切成 {} 块并已入库", title, chunks.size());
        return new IngestResponse(title, chunks.size(), vectorStore.size());
    }

    /**
     * 在线问答流：基于知识库回答问题。
     */
    public AskResponse ask(String question, Integer topK) {
        int k = (topK == null || topK <= 0) ? DEFAULT_TOP_K : topK;

        // 1. 召回 Top-K 相关块
        List<InMemoryVectorStore.Hit> hits = retriever.retrieve(question, k);

        // 2. 过滤掉相似度过低的块（防止拿无关内容喂给模型）
        List<InMemoryVectorStore.Hit> valid = hits.stream()
                .filter(h -> h.score() >= SCORE_THRESHOLD)
                .toList();

        // 3. 知识库没命中 → 明确告知，不让模型瞎编（RAG 的核心价值：不幻觉）
        if (valid.isEmpty()) {
            return new AskResponse(
                    "抱歉，我在知识库中没有找到与该问题相关的资料，无法回答。请先通过 /ingest 写入相关知识。",
                    List.of());
        }

      // 4. 拼装上下文：把召回的原文编号列出
        String context = valid.stream()
                .map(h -> "【来源：" + h.chunk().getChunk().getDocTitle() + "】\n"
                        + h.chunk().getChunk().getContent())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 5. 构建 RAG 提示词：强约束「只依据资料回答」
        String systemPrompt = """
                你是一个严谨的企业知识库助手。请严格遵守以下规则：
                1. 只能依据下面提供的【资料】来回答问题；
                2. 如果资料中没有答案，直接说「资料中未提及」，绝不允许编造；
                3. 回答要简洁、准确，可引用资料中的原文。
                """;
        String userPrompt = "【资料】\n" + context + "\n\n【问题】\n" + question;

        // 6. 调用 LLM 生成答案（真实调用 LM Studio 聊天模型）
        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        // 7. 组装引用出处返回
        List<AskResponse.Reference> refs = valid.stream()
                .map(h -> new AskResponse.Reference(
                        h.chunk().getChunk().getDocTitle(),
                        h.chunk().getChunk().getContent(),
                        h.score()))
                .toList();

        log.info("[RAG-Ask] 问题=\"{}\" 用了 {} 条资料生成答案", question, refs.size());
        return new AskResponse(answer, refs);
    }

    /** 清空知识库（便于反复测试） */
    public void clear() {
        vectorStore.clear();
    }
}