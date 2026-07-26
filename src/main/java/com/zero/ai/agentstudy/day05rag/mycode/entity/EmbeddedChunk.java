package com.zero.ai.agentstudy.day05rag.mycode.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * EmbeddedChunk：已经完成向量化的 Chunk。
 *
 * <p>为什么需要这个类？
 * {@link Chunk} 只有文本，而向量库里真正存储、检索的是「文本 + 它的向量」。
 * 这个类就是「原文 + 向量」的绑定，是向量库里的一条记录。
 *
 * <p>相似检索时：用问题向量和这里的 {@code embedding} 算余弦相似度，
 * 找出最接近的若干条，再取它们的 {@code chunk.content} 拼进 Prompt。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddedChunk {

    /** 原始文本块（含出处信） */
    private Chunk chunk;

    /**
     * 这段文本的向量表示。
     * 维度由 Embedding 模型决定，本项目 Qwen3-Embedding-0.6B 为 1024 维。
     * 语义相近的文本，向量在空间中距离也近。
     */
    private float[] embedding;
}