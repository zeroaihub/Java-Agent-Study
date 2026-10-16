package com.zero.ai.agentstudy.day15knowledgeplatform.document.event;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.event.AbstractDomainEvent;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

/**
 * 文档已删除事件：触发「删除同步」的源头。
 *
 * <p>这是整个知识平台<b>最容易被忽视、也最危险</b>的一个环节。
 * 只删文档记录而不删 Chunk 与向量，用户就会检索到「幽灵知识」——
 * 更糟的是，被删的往往是敏感内容，于是演变成一次数据泄漏事故。</p>
 *
 * <p>sync 模块会监听本事件，级联删除：Chunk 表 → 向量库 → 缓存 → 搜索索引。</p>
 *
 * @author ZeroAi
 */
public class DocumentDeletedEvent extends AbstractDomainEvent {

    private final DocumentId documentId;
    private final KnowledgeBaseId knowledgeBaseId;

    public DocumentDeletedEvent(TenantId tenantId,
                                DocumentId documentId,
                                KnowledgeBaseId knowledgeBaseId) {
        super(tenantId);
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public KnowledgeBaseId knowledgeBaseId() {
        return knowledgeBaseId;
    }
}
