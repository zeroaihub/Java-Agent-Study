package com.zero.ai.agentstudy.day15knowledgeplatform.document;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.Page;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.PageRequest;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.event.DomainEventPublisher;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.event.DocumentDeletedEvent;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.event.DocumentStatusChangedEvent;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.event.DocumentUploadedEvent;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.Document;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.model.DocumentStatus;

import java.util.List;
import java.util.Optional;

/**
 * 文档应用服务（用例编排层）。
 *
 * <p>职责：编排仓储与事件发布，对外提供「上传 / 查询 / 推进状态 / 删除」四个用例。
 * 它<b>不持有业务规则</b>——状态能不能流转由 {@link DocumentStateMachine} 与
 * {@link Document} 聚合根决定，这里只做编排与事务边界。</p>
 *
 * <p>注意所有入口都先取 {@code TenantContext.require()}：租户上下文缺失一律拒绝，
 * 绝不退化成「不限租户的全表扫描」。</p>
 *
 * @author ZeroAi
 */
public class DocumentService {

    private final DocumentRepository repository;
    private final DomainEventPublisher eventPublisher;

    public DocumentService(DocumentRepository repository, DomainEventPublisher eventPublisher) {
        this.repository = KnowledgeException.requireNonNull(repository, "documentRepository");
        this.eventPublisher = KnowledgeException.requireNonNull(eventPublisher, "eventPublisher");
    }

    /**
     * 用例一：上传文档。
     *
     * @return 已持久化的文档，状态为 UPLOADED
     */
    public Document upload(WorkspaceId workspaceId,
                           KnowledgeBaseId knowledgeBaseId,
                           String filename,
                           byte[] content,
                           Metadata metadata) {
        TenantId tenantId = requireTenant();
        Document document = Document.upload(tenantId, workspaceId, knowledgeBaseId,
                filename, content, metadata);
        repository.save(document);
        eventPublisher.publish(new DocumentUploadedEvent(tenantId, document.id(),
                knowledgeBaseId, document.format(), document.version()));
        return document;
    }

    /**
     * 用例二：查询文档（带租户校验）。
     */
    public Optional<Document> get(DocumentId documentId) {
        TenantId tenantId = requireTenant();
        return repository.findById(tenantId, documentId);
    }

    /**
     * 用例二（变体）：分页列出知识库内文档。
     */
    public Page<Document> list(KnowledgeBaseId knowledgeBaseId, PageRequest pageRequest) {
        TenantId tenantId = requireTenant();
        return repository.findByKnowledgeBase(tenantId, knowledgeBaseId, pageRequest);
    }

    /**
     * 用例三：推进状态。
     *
     * <p>统一入口的意义：所有状态变更都要发 {@link DocumentStatusChangedEvent}，
     * 若散落在各模块里各自 {@code setStatus}，事件必然漏发，审计链路就断了。</p>
     *
     * @param transition 状态推进动作，例如 {@code doc -> doc.markParsed()}
     */
    public DocumentStatus advance(DocumentId documentId,
                                  java.util.function.Function<Document, DocumentStatus> transition) {
        TenantId tenantId = requireTenant();
        Document document = requireDocument(tenantId, documentId);
        DocumentStatus before = document.status();
        try {
            DocumentStatus after = transition.apply(document);
            repository.save(document);
            eventPublisher.publish(new DocumentStatusChangedEvent(tenantId, documentId, before, after));
            return after;
        } catch (KnowledgeException ex) {
            // 流水线中任何一步失败，都要把文档置为 FAILED 并留下原因，
            // 否则它会永远卡在「进行中」，既不被重试也不被清理。
            //
            // 但必须先判断 FAILED 是否可达：例如 INDEXED → FAILED 本身就不是合法流转，
            // 若不加判断直接 markFailed，会抛出第二个异常，把真正的失败原因掩盖掉
            // ——这类「异常处理里再抛异常」是排查线上问题时最耗时的坑。
            if (DocumentStateMachine.canFire(document.status(), DocumentStatus.FAILED)) {
                document.markFailed(ex.getMessage());
                repository.save(document);
                eventPublisher.publish(new DocumentStatusChangedEvent(tenantId, documentId,
                        before, DocumentStatus.FAILED));
            }
            throw ex;
        }
    }

    /**
     * 用例三（便捷重载）：直接推进到指定状态。
     */
    public DocumentStatus advanceTo(DocumentId documentId, DocumentStatus target) {
        return advance(documentId, doc -> {
            switch (target) {
                case PARSING -> doc.markParsing();
                case PARSED -> doc.markParsed();
                case CHUNKING -> doc.markChunking();
                case CHUNKED -> doc.markChunked();
                case EMBEDDING -> doc.markEmbedding();
                case EMBEDDED -> doc.markEmbedded();
                case INDEXED -> doc.markIndexed();
                case DELETED -> doc.markDeleted();
                default -> throw KnowledgeException.of(KnowledgeErrorCode.OPERATION_NOT_ALLOWED,
                        "不支持直接推进到状态 " + target);
            }
            return doc.status();
        });
    }

    /**
     * 用例四：删除文档（发出删除事件，供 sync 模块级联清理 Chunk 与向量）。
     */
    public void delete(DocumentId documentId) {
        TenantId tenantId = requireTenant();
        Document document = requireDocument(tenantId, documentId);
        document.markDeleted();
        repository.save(document);
        eventPublisher.publish(new DocumentDeletedEvent(tenantId, documentId, document.knowledgeBaseId()));
        repository.deleteById(tenantId, documentId);
    }

    /**
     * 捞取某状态下的全部文档，供流水线调度器批量驱动。
     */
    public List<Document> pendingOf(DocumentStatus status) {
        TenantId tenantId = requireTenant();
        return repository.findByStatus(tenantId, status);
    }

    private TenantId requireTenant() {
        return com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext.require();
    }

    private Document requireDocument(TenantId tenantId, DocumentId documentId) {
        return repository.findById(tenantId, documentId)
                .orElseThrow(() -> KnowledgeException.of(KnowledgeErrorCode.DOCUMENT_NOT_FOUND,
                        "文档不存在: " + documentId));
    }
}
