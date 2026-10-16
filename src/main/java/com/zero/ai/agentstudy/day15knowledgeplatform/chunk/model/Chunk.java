package com.zero.ai.agentstudy.day15knowledgeplatform.chunk.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.ChunkId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;

/**
 * 切片（Chunk）—— 检索的最小单元。
 *
 * <p><b>为什么这里用 record（不可变）而不是可变类？</b></p>
 *
 * <p>第 1 章说过 Chunk 是实体，但它是一类特殊的实体：<b>创建后即定型</b>。
 * 文本变了？那不是「修改 Chunk」，而是一次重新切分——因为向量必须重算，
 * 元数据（序号、字符数）也全变了。允许原地修改 Chunk 只会制造
 * 「文本已改、向量未更新」的不一致状态，这正是「向量漂移」的温床。</p>
 *
 * <p>所以 Chunk 用不可变 record：想改，就重新切一份。</p>
 *
 * @param id               切片 ID
 * @param tenantId         租户（冗余存储，供向量库做行级过滤）
 * @param workspaceId      工作空间
 * @param knowledgeBaseId  知识库（<b>检索作用域</b>，第 7 章的核心过滤维度）
 * @param documentId       所属文档
 * @param sequence         在文档内的序号，从 0 开始（用于拼装上下文与溯源）
 * @param text             切片文本
 * @param charCount        字符数
 * @param tokenEstimate    估算 Token 数（用于控制 Prompt 预算）
 * @param metadata         元数据（标题、来源、章节路径等）
 * @author ZeroAi
 */
public record Chunk(ChunkId id,
                    TenantId tenantId,
                    WorkspaceId workspaceId,
                    KnowledgeBaseId knowledgeBaseId,
                    DocumentId documentId,
                    int sequence,
                    String text,
                    int charCount,
                    int tokenEstimate,
                    Metadata metadata) {

    public Chunk {
        KnowledgeException.requireNonNull(id, "chunkId");
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        KnowledgeException.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        KnowledgeException.requireNonNull(documentId, "documentId");
        KnowledgeException.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.CHUNK_FAILED, "切片文本不能为空");
        }
        if (sequence < 0) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "切片序号不能为负");
        }
        if (metadata == null) {
            metadata = Metadata.empty();
        }
    }

    /**
     * 生成切片预览（用于日志与检索结果展示）。
     */
    public String preview(int maxLength) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= maxLength ? t : t.substring(0, maxLength) + "…";
    }

    @Override
    public String toString() {
        return "Chunk{" + id + ", seq=" + sequence + ", chars=" + charCount
                + ", " + preview(30) + "}";
    }
}
