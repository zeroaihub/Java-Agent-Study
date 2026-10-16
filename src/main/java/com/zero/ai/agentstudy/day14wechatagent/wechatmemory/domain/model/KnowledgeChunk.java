package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model;

import java.util.Objects;

/**
 * 知识切片（长期记忆的最小检索单元）。
 *
 * <p>企业知识库的文档往往很长，无法整篇喂给模型，也不利于精准检索。
 * RAG（Retrieval-Augmented Generation，检索增强生成）的第一步是把文档切成若干
 * 语义相对完整的<strong>切片（chunk）</strong>，再为每个切片计算<strong>向量（embedding）</strong>。
 * 检索时用用户问题的向量与切片向量做相似度比较，召回最相关的若干切片作为上下文。</p>
 *
 * <p>一个 {@code KnowledgeChunk} 记录：所属租户、所属文档、切片序号、文本内容与向量表示。
 * 它是不可变值对象。真实生产环境中，{@code embedding} 会持久化到 pgvector 等向量数据库，
 * 本教程用内存实现占位，但模型结构保持一致。</p>
 *
 * @param tenantId   租户（多租户隔离，检索必须带租户过滤）
 * @param documentId 所属文档 ID
 * @param chunkIndex 切片在文档内的序号（从 0 开始）
 * @param content    切片文本内容
 * @param embedding  切片的向量表示（可为 null，表示尚未向量化）
 * @author ZeroHub AI Agent Platform
 */
public record KnowledgeChunk(
        String tenantId,
        String documentId,
        int chunkIndex,
        String content,
        float[] embedding) {

    /**
     * 紧凑构造器：做非空校验与文本规范化。
     */
    public KnowledgeChunk {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(documentId, "documentId 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        content = content.strip();
    }

    /**
     *构造一个尚未向量化的切片。
     *
     * @param tenantId   租户
     * @param documentId 文档 ID
     * @param chunkIndex 切片序号
     * @param content    文本内容
     * @return 无向量的切片
     */
    public static KnowledgeChunk of(String tenantId, String documentId, int chunkIndex, String content) {
        return new KnowledgeChunk(tenantId, documentId, chunkIndex, content, null);
    }

    /**
     * 基于当前切片补充向量，返回一个新的切片实例（不可变，原实例不变）。
     *
     * @param embedding 计算好的向量
     * @return 带向量的新切片
     */
    public KnowledgeChunk withEmbedding(float[] embedding) {
        return new KnowledgeChunk(tenantId, documentId, chunkIndex, content, embedding);
    }

    /**
     * @return 是否已完成向量化
     */
    public boolean hasEmbedding() {
        return embedding != null && embedding.length > 0;
    }

    /**
     * 该切片的稳定标识：{@code documentId#chunkIndex}。
     *
     * @return 切片 ID
     */
    public String chunkId() {
        return documentId + "#" + chunkIndex;
    }
}