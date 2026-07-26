package com.zero.ai.agentstudy.day05rag.embedding;

/**
 * EmbeddingClient —— 「文本 → 向量」的能力接口（面向接口设计）。
 *
 * <p>为什么抽成接口：把文本变成向量有三种来源——云端 API（OpenAI/通义）、
 * 本地模型、离线降级。上层（入库流程、Retriever）不该关心用哪种。
 * 抽成接口后可随意替换实现，且入库与查询共享同一实现，保证第二章「铁律①：
 * 查询与入库必须用同一个 Embedding 模型」。</p>
 *
 * <p>实现类：</p>
 * <ul>
 *   <li>{@link HashEmbeddingClient} —— 离线哈希降级实现（本章教学用，可离线跑）</li>
 *   <li>（未来）OpenAiEmbeddingClient —— 云端 API 实现</li>
 *   <li>（未来）LocalModelClient —— 本地模型实现</li>
 * </ul>
 *
 * @author ZeroAi
 */
public interface EmbeddingClient {

    /**
     * 把一段文本转换成向量。
     *
     * @param text 输入文（问题或文档片段）
     * @return 定长向量，供相似度计算使用
     */
    float[] embed(String text);

    /**
     * 返回该实现输出向量的维度。
     *
     * <p>用途：入库与查询维度必须一致，可用它做校验。</p>
     *
     * @return 向量维度
     */
    int dimension();
}