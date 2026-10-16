package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.KnowledgeChunk;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.KnowledgeDocument;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.RetrievalResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port.EmbeddingProvider;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库应用服务（RAG 编排）。
 *
 * <p>封装 RAG 的两条主链路：</p>
 * <ol>
 *     <li><strong>入库（ingest）</strong>：文档 -> 切片 -> 向量化 -> 存入向量库。
 *         由 {@link #ingest(KnowledgeDocument)} 完成。</li>
 *     <li><strong>检索增强（retrieve & augment）</strong>：问题 -> 向量化 -> 相似检索 ->
 *         拼装为「带知识上下文的提示词」。由 {@link #retrieve(String, String, int)} 与
 *         {@link #augment(String, String, int)} 完成。</li>
 * </ol>
 *
 * <p>它只依赖两个端口——{@link EmbeddingProvider}（向量化）与 {@link KnowledgeStore}（存储检索），
 * 与具体的模型、向量库解耦。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /** 每个切片的目标字符数。生产环境常取 300~800，并配合重叠窗口。 */
    private static final int CHUNK_SIZE = 300;

    /** 相邻切片的重叠字符数，避免语义在切片边界被割裂。 */
    private static final int CHUNK_OVERLAP = 50;

    /** 默认召回条数。 */
    private static final int DEFAULT_TOP_K = 3;

    /** 相似度阈值：低于此值的召回视为噪声丢弃。 */
    private static final double MIN_SCORE = 0.1;

    private final EmbeddingProvider embeddingProvider;
    private final KnowledgeStore knowledgeStore;

    /**
     * 构造函数注入向量化与存储两个端口。
     *
     * @param embeddingProvider 向量化端口
     * @param knowledgeStore    知识库存储端口
     */
    public KnowledgeService(EmbeddingProvider embeddingProvider, KnowledgeStore knowledgeStore) {
        this.embeddingProvider = embeddingProvider;
        this.knowledgeStore = knowledgeStore;
    }

    /**
     * 文档入库：切片 -> 向量化 -> 存储。
     *
     * @param document 待入库文档
     * @return 生成并入库的切片数
     */
    public int ingest(KnowledgeDocument document) {
        List<String> pieces = splitIntoChunks(document.content());
        List<KnowledgeChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            float[] vector = embeddingProvider.embed(pieces.get(i));
            chunks.add(KnowledgeChunk
                    .of(document.tenantId(), document.documentId(), i, pieces.get(i))
                    .withEmbedding(vector));
        }
        knowledgeStore.saveAll(chunks);
        log.info("文档入库完成: tenant={}, doc={}, title='{}', chunks={}",
                document.tenantId(), document.documentId(), document.title(), chunks.size());
        return chunks.size();
    }

    /**
     * 相似检索：把问题向量化后在知识库中召回 topK，并过滤低分噪声。
     *
     * @param tenantId 租户
     * @param question 用户问题
     * @param topK     召回条数；<=0 时用默认值
     * @return 检索结果（按相似度降序，已过滤低分）
     */
    public List<RetrievalResult> retrieve(String tenantId, String question, int topK) {
        int k = topK > 0 ? topK : DEFAULT_TOP_K;
        float[] queryVector = embeddingProvider.embed(question);
        List<RetrievalResult> hits = knowledgeStore.search(tenantId, queryVector, k);
        List<RetrievalResult> filtered = hits.stream()
                .filter(r -> r.score() >= MIN_SCORE)
                .toList();
        log.debug("知识检索: tenant={}, question='{}', hits={}, kept={}",
                tenantId, question, hits.size(), filtered.size());
        return filtered;
    }

    /**
     * 检索增强：检索相关知识并拼装成「带上下文的提示词」，交给 LLM 生成答案。
     *
     * <p>这是 RAG 的核心动作——把「你自己回答」变成「基于以下资料回答」，
     * 显著降低幻觉、提升企业知识的准确性与可溯源性。若无相关知识则原样返回问题。</p>
     *
     * @param tenantId 租户
     * @param question 用户问题
     * @param topK     召回条数
     * @return 增强后的提示词
     */
    public String augment(String tenantId, String question, int topK) {
        List<RetrievalResult> hits = retrieve(tenantId, question, topK);
        if (hits.isEmpty()) {
            return question;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append("请依据以下企业知识库资料回答用户问题；若资料不足以回答，请如实说明，不要编造。\n\n");
        prompt.append("=== 知识库资料 ===\n");
        int idx = 1;
        for (RetrievalResult hit : hits) {
            prompt.append("[资料").append(idx++).append("] ")
                    .append(hit.content())
                    .append("\n");
        }
        prompt.append("\n=== 用户问题 ===\n").append(question);
        return prompt.toString();
    }

    /**
     * 下线一篇文档的所有切片。
     *
     * @param tenantId   租户
     * @param documentId 文档 ID
     */
    public void remove(String tenantId, String documentId) {
        knowledgeStore.deleteByDocument(tenantId, documentId);
    }

    /**
     * 简单的定长重叠切片器。
     *
     * <p>按固定窗口 {@link #CHUNK_SIZE} 滑动切分，相邻切片重叠 {@link #CHUNK_OVERLAP} 个字符，
     * 避免关键信息恰好落在切片边界而被割裂。生产环境可升级为「按句子/段落 + 语义边界」切分。</p>
     *
     * @param content 文档正文
     * @return 切片文本列表
     */
    private List<String> splitIntoChunks(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        String text = content.strip();
        int step = Math.max(1, CHUNK_SIZE - CHUNK_OVERLAP);
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(text.length(), start + CHUNK_SIZE);
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
        }
        return chunks;
    }
}