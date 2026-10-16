package com.zero.ai.agentstudy.day15knowledgeplatform.document.event;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.event.AbstractDomainEvent;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus;

/**
 * 文档状态变更事件。
 *
 * <p>用途有三：</p>
 * <ol>
 *     <li><b>可观测性</b>：统计各状态耗时，定位流水线瓶颈。</li>
 *     <li><b>下游触发</b>：进入 PARSED 触发切分、进入 CHUNKED 触发向量化。</li>
 *     <li><b>审计</b>：谁在什么时候把文档置成了什么状态，全程留痕。</li>
 * </ol>
 *
 * @author ZeroAi
 */
public class DocumentStatusChangedEvent extends AbstractDomainEvent {

    private final DocumentId documentId;
    private final DocumentStatus from;
    private final DocumentStatus to;

    public DocumentStatusChangedEvent(TenantId tenantId,
                                      DocumentId documentId,
                                      DocumentStatus from,
                                      DocumentStatus to) {
        super(tenantId);
        this.documentId = documentId;
        this.from = from;
        this.to = to;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public DocumentStatus from() {
        return from;
    }

    public DocumentStatus to() {
        return to;
    }

    @Override
    public String toString() {
        return "DocumentStatusChangedEvent{" + documentId + ", " + from + " -> " + to + "}";
    }
}
