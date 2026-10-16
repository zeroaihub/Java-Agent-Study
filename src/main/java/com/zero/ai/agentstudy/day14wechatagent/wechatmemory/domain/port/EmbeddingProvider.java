package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port;

import java.util.List;

/**
 * 向量化端口（Embedding Provider）。
 *
 * <p>把文本转换为固定维度的浮点向量（embedding），是 RAG 的基石。
 * 语义相近的文本，其向量在空间中的距离（或余弦相似度）也相近。</p>
 *
 * <p>这是一个<strong>领域端口</strong>：应用层只依赖此接口，不关心背后是
 * OpenAI text-embedding、BGE、还是本地模型。本教程用一个确定性的伪向量实现占位
 * （基于词哈希），保证「相同文本得到相同向量、相关文本向量相近」的基本性质，
 * 生产环境替换为 Spring AI 的 {@code EmbeddingModel} 适配器即可。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public interface EmbeddingProvider {

    /**
     * 将单条文本向量化。
     *
     * @param text 输入文本
     * @return 向量（float 数组）
     */
    float[] embed(String text);

    /**
     * 批量向量化（默认逐条调用，实现可覆盖为真正的批处理以提升吞吐）。
     *
     * @param texts 输入文本列表
     * @return 与输入等长的向量列表
     */
    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /**
     * @return 向量维度（用于校验与初始化向量库）
     */
    int dimension();
}