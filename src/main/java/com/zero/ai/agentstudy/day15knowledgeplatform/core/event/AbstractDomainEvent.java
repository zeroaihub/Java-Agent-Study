package com.zero.ai.agentstudy.day15knowledgeplatform.core.event;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

import java.time.Instant;
import java.util.UUID;

/**
 * 领域事件抽象基类，统一生成 eventId / occurredAt，子类只需补充业务字段。
 *
 * <p>使用示例：
 * <pre>{@code
 * public final class DocumentDeletedEvent extends AbstractDomainEvent {
 *     private final DocumentId documentId;
 *     public DocumentDeletedEvent(TenantId tenantId, DocumentId documentId) {
 *         super(tenantId);
 *         this.documentId = documentId;
 *     }
 *     public DocumentId documentId() { return documentId; }
 * }
 * }</pre>
 *
 * @author ZeroAi
 */
public abstract class AbstractDomainEvent implements DomainEvent {

    private final String eventId;
    private final TenantId tenantId;
    private final Instant occurredAt;

    protected AbstractDomainEvent(TenantId tenantId) {
        this.eventId = UUID.randomUUID().toString();
        this.tenantId = KnowledgeException.requireNonNull(tenantId, "tenantId");
        this.occurredAt = Instant.now();
    }

    @Override
    public String eventId() {
        return eventId;
    }

    @Override
    public TenantId tenantId() {
        return tenantId;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }
}