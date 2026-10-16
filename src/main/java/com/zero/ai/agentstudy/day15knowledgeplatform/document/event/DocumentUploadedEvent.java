package com.zero.ai.agentstudy.day15knowledgeplatform.document.event;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.event.AbstractDomainEvent;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentFormat;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentVersion;

/**
 * 文档已上传事件：整条 Ingestion 流水线的起点。
 *
 * <p>parser 模块监听它开始解析，chunk 模块监听解析完成事件开始切分……
 * 各阶段通过事件串联，而不是在一个 Service 里顺序调用到底。</p>
 *
 * <p><b>为什么用事件而不是直接调用？</b> 因为流水线阶段会不断增加（未来加 OCR、加翻译、加去重），
 * 直接调用会让 Service 膨胀成上帝类；用事件则新增阶段只需加一个监听器，符合开闭原则。</p>
 *
 * @author ZeroAi
 */
public class DocumentUploadedEvent extends AbstractDomainEvent {

    private final DocumentId documentId;
    private final KnowledgeBaseId knowledgeBaseId;
    private final DocumentFormat format;
    private final DocumentVersion version;

    public DocumentUploadedEvent(TenantId tenantId,
                                 DocumentId documentId,
                                 KnowledgeBaseId knowledgeBaseId,
                                 DocumentFormat format,
                                 DocumentVersion version) {
        super(tenantId);
        this.documentId = documentId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.format = format;
        this.version = version;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public KnowledgeBaseId knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public DocumentFormat format() {
        return format;
    }

    public DocumentVersion version() {
        return version;
    }
}
