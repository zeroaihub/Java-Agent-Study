package com.zero.ai.agentstudy.day05rag.entity;

/**
 * SearchResult —— 一次检索命中的「一条结果」= 命中的 Chunk + 相似度得分。
 *
 * <p>为什么需要它：检索不只要返回「哪条 chunk」，还要返回「有多像(score)」。
 * score 用于排序、以及后续设「相似度阈值」判断是否真的相关。</p>
 *
 * @author ZeroAi
 */
public class SearchResult {

    /** 命中的 chunk（含原文与元数据） */
    private final Chunk chunk;

    /** 与查询向量的相似度得分，越大越相似 */
    private final double score;

    public SearchResult(Chunk chunk, double score) {
        this.chunk = chunk;
        this.score = score;
    }

    public Chunk getChunk() {
        return chunk;
    }

    public double getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "SearchResult{score=" + String.format("%.4f", score)
                + ", content='" + chunk.getContent() + "'}";
    }
}