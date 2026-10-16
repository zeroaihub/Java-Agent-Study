package com.zero.ai.agentstudy.day15knowledgeplatform.embedding.adapter;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddingVector;
import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.port.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 适配器：把 Spring AI 的 {@code EmbeddingModel} 适配到本模块的端口。
 *
 * <p>这是六边形架构里最典型的<b>适配器（Adapter）</b>：
 * 它唯一的工作，就是把外部世界（Spring AI）的类型与语义，
 * 翻译成我们领域内部的类型与语义。除了这个类，整个 Day15 没有任何一行代码
 * 依赖 Spring AI——这就是端口与适配器带来的隔离收益。</p>
 *
 * <p><b>两个必须处理的工程细节</b>：</p>
 * <ol>
 *     <li><b>归一化</b>：各家模型返回的向量未必已归一化（OpenAI 是归一化的，
 *         但不少本地模型不是）。我们在适配层统一 L2 归一化，
 *         让下游检索可以放心地用点积代替余弦。</li>
 *     <li><b>批量调用</b>：优先走 {@code embed(List<String>)} 批量接口，
 *         把 N 次网络往返压缩成 1 次。这是 Embedding 阶段最大的一笔性能优化。</li>
 * </ol>
 *
 * <p><b>模型标识的来源</b>：{@code modelId} 必须由调用方显式传入，而不是从 Spring AI
 * 的 bean 里反射读取。原因见 {@link EmbeddingVector}——模型标识要写进每条向量用于
 * 「锁版本」校验，它必须是<b>稳定、可读、可比对</b>的，不能依赖框架内部实现细节。</p>
 *
 * @author ZeroAi
 */
public class SpringAiEmbeddingModel implements EmbeddingModel {

    private final org.springframework.ai.embedding.EmbeddingModel delegate;
    private final String modelId;

    /**
     * @param delegate Spring AI 的 Embedding 模型（由 Spring 容器注入）
     * @param modelId  模型标识（含版本），例如 {@code text-embedding-nomic-embed-text-v1.5}
     */
    public SpringAiEmbeddingModel(org.springframework.ai.embedding.EmbeddingModel delegate, String modelId) {
        KnowledgeException.requireNonNull(delegate, "springAiEmbeddingModel");
        if (modelId == null || modelId.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT,
                    "modelId 不能为空：更换 Embedding 模型必须全量重建向量，故必须显式声明模型标识");
        }
        this.delegate = delegate;
        this.modelId = modelId;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int dimension() {
        return delegate.dimensions();
    }

    @Override
    public EmbeddingVector embed(String text) {
        KnowledgeException.requireNonNull(text, "text");
        try {
            return new EmbeddingVector(modelId, dimension(),
                    EmbeddingVector.l2Normalize(delegate.embed(text)), true);
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                    "调用 Embedding 模型失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public List<EmbeddingVector> embedAll(List<String> texts) {
        KnowledgeException.requireNonNull(texts, "texts");
        if (texts.isEmpty()) {
            return List.of();
        }
        try {
            List<float[]> raw = delegate.embed(texts);
            if (raw.size() != texts.size()) {
                throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                        "Embedding 返回条数不匹配：期望 " + texts.size() + "，实际 " + raw.size());
            }
            List<EmbeddingVector> out = new ArrayList<>(raw.size());
            for (float[] v : raw) {
                out.add(new EmbeddingVector(modelId, v.length, EmbeddingVector.l2Normalize(v), true));
            }
            return out;
        } catch (KnowledgeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                    "批量调用 Embedding 模型失败：" + ex.getMessage(), ex);
        }
    }
}
