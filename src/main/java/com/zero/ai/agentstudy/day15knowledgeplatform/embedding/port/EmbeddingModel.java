package com.zero.ai.agentstudy.day15knowledgeplatform.embedding.port;

import com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model.EmbeddingVector;

import java.util.List;

/**
 * 向量化模型端口（出站端口 / Driven Port）。
 *
 * <p>六边形架构里，模型供应商是<b>外部资源</b>，因此抽象成端口：
 * 领域与编排层只依赖本接口，不依赖 OpenAI / Ollama / 本地 ONNX 的任何具体类型。
 * 换供应商 = 换一个适配器实现，业务代码一行不改。</p>
 *
 * <p>为什么<b>批量方法 {@link #embedAll} 是必须的一等公民</b>，而不是让调用方循环调单条？
 * 因为 Embedding 调用的瓶颈从来不是算力，而是<b>网络往返</b>。
 * 一次请求 100 条和 100 次请求各 1 条，服务端算力几乎一样，但后者要多付 99 次
 * RTT（跨公网常常是 20~80ms/次），整体耗时能差一个数量级。
 * 生产上这条优化通常是 Embedding 阶段最大的一笔性能收益。</p>
 *
 * @author ZeroAi
 */
public interface EmbeddingModel {

    /**
     * 模型标识（含版本），例如 {@code text-embedding-nomic-embed-text-v1.5}。
     *
     * <p>它会被写进每一条向量，作为「模型锁版本」的校验依据。</p>
     */
    String modelId();

    /**
     * 向量维度。查询向量与库内向量维度不一致时立即失败。
     */
    int dimension();

    /**
     * 对单条文本向量化。
     */
    EmbeddingVector embed(String text);

    /**
     * 批量向量化，返回顺序与入参严格一致。
     *
     * @param texts 文本列表
     * @return 向量列表，与入参等长、同序
     */
    List<EmbeddingVector> embedAll(List<String> texts);
}
