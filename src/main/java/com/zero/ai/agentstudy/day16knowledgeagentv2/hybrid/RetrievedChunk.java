package com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.util.Set;

/**
 * 检索命中的知识片段（Chapter 05）。
 *
 * <p>这是贯穿「检索 → 重排 → ACL → 引用」全链路的核心数据载体。它既是向量检索的结果，
 * 也是 BM25 检索的结果，还是后续 RRF 融合、Rerank、ACL 过滤的输入。</p>
 *
 * @param chunkId    片段唯一标识
 * @param documentId 所属文档
 * @param title      文档标题（用于引用展示）
 * @param content    片段正文
 * @param score      本次检索打分（不同检索源分数不可直接比较，需靠 RRF 归一）
 * @param aclLabels  访问控制标签（供 Chapter 07 的 ACL 过滤使用）
 * @author ZeroAi
 */
public record RetrievedChunk(
        String chunkId,
        String documentId,
        String title,
        String content,
        double score,
        Set<String> aclLabels
) {

    public RetrievedChunk {
        AgentException.requireText(chunkId, "chunkId");
        AgentException.requireText(documentId, "documentId");
        aclLabels = aclLabels == null ? Set.of() : Set.copyOf(aclLabels);
    }

    /**
     * 复制并替换分数（RRF 融合后重建对象，保持 record 不可变）。
     */
    public RetrievedChunk withScore(double newScore) {
        return new RetrievedChunk(chunkId, documentId, title, content, newScore, aclLabels);
    }
}