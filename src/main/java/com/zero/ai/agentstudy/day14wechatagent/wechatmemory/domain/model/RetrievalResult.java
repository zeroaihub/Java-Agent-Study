package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model;

import java.util.Objects;

/**
 * 知识检索结果（一条召回记录）。
 *
 * <p>RAG 检索阶段的产物：给定用户问题的向量，在知识库中比对相似度后，
 * 返回若干「切片 + 相似度分数」对。分数越高越相关（本教程用余弦相似度，范围约 [-1, 1]）。</p>
 *
 * <p>该结果同时携带原始 {@link KnowledgeChunk}，便于上层在拼装提示词时
* 引用其 {@code content} 与来源信息，实现「答案可溯源」。</p>
 *
 * @param chunk 命中的知识切片
 * @param score 相似度分数（越大越相关）
 * @author ZeroHub AI Agent Platform
 */
public record RetrievalResult(KnowledgeChunk chunk, double score) implements Comparable<RetrievalResult> {

    /**
     * 紧凑构造器：校验切片非空。
     */
    public RetrievalResult {
        Objects.requireNonNull(chunk, "chunk 不能为空");
    }

    /**
     * 按相似度<strong>降序</strong>排序（分数高的排前面）。
     *
     * @param other 另一条结果
     * @return 比较值
     */
    @Override
    public int compareTo(RetrievalResult other) {
        return Double.compare(other.score, this.score);
    }

    /**
     * @return 命中切片的文本内容
     */
    public String content() {
        return chunk.content();
    }

    /**
     * @return 命中切片的来源文档 ID
     */
    public String documentId() {
        return chunk.documentId();
    }

    /**
     * @return 便于日志展示的一行摘要
     */
    public String summary() {
        String c = chunk.content();
        String preview = c.length() > 40 ? c.substring(0, 40) + "..." : c;
        return "[score=%.4f] %s".formatted(score, preview);
    }
}