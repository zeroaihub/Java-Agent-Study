package com.zero.ai.agentstudy.day15knowledgeplatform.embedding.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model.Chunk;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

/**
 * 已向量化的切片（Chunk + EmbeddingVector）。
 *
 * <p>它是 <b>切分</b> 与 <b>存储</b> 之间的一道重要闸门。
 * 有了它，「文本向量化」与「写入向量库」就能被拆成两个可独立失败、可独立重试的阶段：</p>
 *
 * <pre>
 *   Chunk  ──(EmbeddingService)──▶  EmbeddedChunk  ──(VectorStore)──▶  已入库
 *           网络密集、可重试               本地/DB 写入、可批量
 * </pre>
 *
 * <p>如果这两件事耦合在一个方法里，一次网络抖动就会导致「已算好的向量连同已写入的
 * 一半数据一起回滚」，重试时又得重新算一遍 Embedding——<b>钱和时间的双重浪费</b>。
 * 分开之后，失败了只需要重放写入阶段。</p>
 *
 * @param chunk    原始切片
 * @param vector   该切片对应的向量
 * @author ZeroAi
 */
public record EmbeddedChunk(Chunk chunk, EmbeddingVector vector) {

    public EmbeddedChunk {
        KnowledgeException.requireNonNull(chunk, "chunk");
        KnowledgeException.requireNonNull(vector, "vector");
    }

    /**
     * 用于向量库主键/幂等写入的键：文档 ID + 切片序号。
     *
     * <p>用业务键而不是随机 UUID，是为了让<b>重复入库天然幂等</b>——
     * 同一份文档重新导入时覆盖同一批 key，不会残留旧向量。</p>
     */
    public String stableKey() {
        return chunk.documentId().value() + "#" + chunk.sequence();
    }

    @Override
    public String toString() {
        return "EmbeddedChunk{" + stableKey() + ", " + vector + "}";
    }
}
