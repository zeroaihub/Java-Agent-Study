package com.zero.ai.agentstudy.day15knowledgeplatform.document.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.DocumentId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.document.DocumentStateMachine;

import java.time.Instant;

/**
 * 文档聚合根（Entity）。
 *
 * <p>与第 1 章的 {@code TenantId} / {@code Metadata} 等值对象不同，Document 是<b>实体</b>：
 * 它有唯一标识 {@link DocumentId}，并且有完整的生命周期（{@link DocumentStatus}）。</p>
 *
 * <p><b>聚合根的核心职责是守护不变量</b>。本类守护三条：</p>
 * <ol>
 *     <li><b>租户不可变</b>：文档一经创建，{@code tenantId} 永不改变，且任何访问都要过 {@link #assertBelongsTo(TenantId)}。</li>
 *     <li><b>状态只按状态机流转</b>：外部拿不到 {@code setStatus}，只能通过 {@code markXxx()} 走
 *         {@link DocumentStateMachine} 校验。</li>
 *     <li><b>原文可追溯</b>：保留原始字节，使解析/切分/重跑随时可重放。</li>
 * </ol>
 *
 * <p>为什么实体是可变的、而值对象不可变？因为实体的「同一性」由 ID 决定，
 * 它的属性随时间演化是业务事实（状态会推进、版本会增长）；值对象的「同一性」由值决定，
 * 改了值就是另一个对象。</p>
 *
 * @author ZeroAi
 */
public final class Document {

    /** 平台允许的单文档最大字节数（50MB）。生产应做成配置并按租户分级。 */
    public static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    private final DocumentId id;
    private final TenantId tenantId;
    private final WorkspaceId workspaceId;
    private final KnowledgeBaseId knowledgeBaseId;
    private final String title;
    private final DocumentFormat format;
    private final byte[] content;
    private final long sizeInBytes;
    private final String checksum;
    private final Instant createdAt;

    private DocumentVersion version;
    private DocumentStatus status;
    private Metadata metadata;
    private Instant updatedAt;
    private String failureReason;

    private Document(DocumentId id,
                     TenantId tenantId,
                     WorkspaceId workspaceId,
                     KnowledgeBaseId knowledgeBaseId,
                     String title,
                     DocumentFormat format,
                     byte[] content,
                     String checksum,
                     DocumentVersion version,
                     Metadata metadata) {
        this.id = KnowledgeException.requireNonNull(id, "documentId");
        this.tenantId = KnowledgeException.requireNonNull(tenantId, "tenantId");
        this.workspaceId = KnowledgeException.requireNonNull(workspaceId, "workspaceId");
        this.knowledgeBaseId = KnowledgeException.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        this.title = KnowledgeException.requireNonNull(title, "title");
        this.format = KnowledgeException.requireNonNull(format, "format");
        this.content = KnowledgeException.requireNonNull(content, "content");
        this.checksum = checksum;
        this.version = version == null ? DocumentVersion.FIRST : version;
        this.metadata = metadata == null ? Metadata.empty() : metadata;
        this.sizeInBytes = content.length;
        this.status = DocumentStatus.UPLOADED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 上传一份新文档（工厂方法，聚合的出生点）。
     *
     * @throws KnowledgeException 超过大小限制时抛 {@link KnowledgeErrorCode#DOCUMENT_TOO_LARGE}
     */
    public static Document upload(TenantId tenantId,
                                  WorkspaceId workspaceId,
                                  KnowledgeBaseId knowledgeBaseId,
                                  String filename,
                                  byte[] content,
                                  Metadata metadata) {
        DocumentFormat format = DocumentFormat.requireSupported(filename);
        if (content == null || content.length == 0) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT, "文档内容不能为空");
        }
        if (content.length > MAX_SIZE_BYTES) {
            throw KnowledgeException.of(KnowledgeErrorCode.DOCUMENT_TOO_LARGE,
                    "文档大小 " + content.length + " 字节，超过限制 " + MAX_SIZE_BYTES + " 字节");
        }
        return new Document(DocumentId.newId(), tenantId, workspaceId, knowledgeBaseId,
                filename, format, content, checksumOf(content), DocumentVersion.FIRST, metadata);
    }

    /**
     * 基于已有文档重新上传新版本：ID 不变、版本号 +1、状态回到 UPLOADED 等待重新入库。
     *
     * <p>这是「文档更新」的正确建模方式——不是原地改内容，
     * 而是<b>生成新版本并触发整条流水线重跑</b>，旧版本的 Chunk 与向量由 sync 模块级联清理。</p>
     */
    public Document nextVersion(byte[] content, String filename) {
        DocumentFormat newFormat = DocumentFormat.requireSupported(filename);
        if (content == null || content.length == 0) {
            throw KnowledgeException.of(KnowledgeErrorCode.INVALID_ARGUMENT, "文档内容不能为空");
        }
        Document next = new Document(this.id, this.tenantId, this.workspaceId, this.knowledgeBaseId,
                filename, newFormat, content, checksumOf(content), this.version.next(), this.metadata);
        return next;
    }

    // ---------------- 状态推进（全部经过状态机校验） ----------------

    private DocumentStatus transitTo(DocumentStatus target) {
        this.status = DocumentStateMachine.fire(this.status, target);
        this.updatedAt = Instant.now();
        if (target != DocumentStatus.FAILED) {
            this.failureReason = null;
        }
        return this.status;
    }

    public DocumentStatus markParsing() {
        return transitTo(DocumentStatus.PARSING);
    }

    public DocumentStatus markParsed() {
        return transitTo(DocumentStatus.PARSED);
    }

    public DocumentStatus markChunking() {
        return transitTo(DocumentStatus.CHUNKING);
    }

    public DocumentStatus markChunked() {
        return transitTo(DocumentStatus.CHUNKED);
    }

    public DocumentStatus markEmbedding() {
        return transitTo(DocumentStatus.EMBEDDING);
    }

    public DocumentStatus markEmbedded() {
        return transitTo(DocumentStatus.EMBEDDED);
    }

    public DocumentStatus markIndexed() {
        return transitTo(DocumentStatus.INDEXED);
    }

    /**
     * 标记处理失败，记录失败原因以便重试时定位。
     */
    public DocumentStatus markFailed(String reason) {
        this.failureReason = reason;
        return transitTo(DocumentStatus.FAILED);
    }

    public DocumentStatus markDeleted() {
        return transitTo(DocumentStatus.DELETED);
    }

    // ---------------- 不变量守护 ----------------

    /**
     * 断言文档属于指定租户，跨租户访问直接抛异常。
     *
     * <p>这是多租户系统的<b>最后一道防线</b>：即便上层查询漏写了 {@code tenant_id} 过滤条件，
     * 聚合根也会在这里拦下来，绝不把数据交出去。</p>
     */
    public void assertBelongsTo(TenantId callerTenantId) {
        if (!this.tenantId.equals(callerTenantId)) {
            throw KnowledgeException.of(KnowledgeErrorCode.TENANT_MISMATCH,
                    "文档 " + id + " 不属于租户 " + callerTenantId);
        }
    }

    /**
     * 断言文档处于可处理状态（未删除）。
     */
    public void requireActive() {
        if (this.status == DocumentStatus.DELETED) {
            throw KnowledgeException.of(KnowledgeErrorCode.DOCUMENT_STATE_ILLEGAL,
                    "文档 " + id + " 已删除，无法继续处理");
        }
    }

    /**
     * 追加元数据（返回新 Document 的元数据视图，聚合本身保持封装）。
     */
    public void enrichMetadata(Metadata extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        this.metadata = this.metadata.merge(extra);
        this.updatedAt = Instant.now();
    }

    // ---------------- 访问器 ----------------

    public DocumentId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public KnowledgeBaseId knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public String title() {
        return title;
    }

    public DocumentFormat format() {
        return format;
    }

    /**
     * 原始内容。返回副本，防止外部通过数组引用篡改聚合内部状态。
     */
    public byte[] content() {
        return content.clone();
    }

    public long sizeInBytes() {
        return sizeInBytes;
    }

    public String checksum() {
        return checksum;
    }

    public DocumentVersion version() {
        return version;
    }

    public DocumentStatus status() {
        return status;
    }

    public Metadata metadata() {
        return metadata;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String failureReason() {
        return failureReason;
    }

    /**
     * 简易校验和，用于增量同步时判断「内容是否真的变了」。
     *
     * <p>生产环境应用 SHA-256；这里用长度 + 采样哈希，只为演示「内容指纹」这一概念且保持零依赖。</p>
     */
    private static String checksumOf(byte[] content) {
        int hash = 1;
        int step = Math.max(1, content.length / 64);
        for (int i = 0; i < content.length; i += step) {
            hash = hash * 31 + (content[i] & 0xff);
        }
        return content.length + "-" + Integer.toHexString(hash);
    }

    @Override
    public String toString() {
        return "Document{" + id + ", title='" + title + "', " + format + ", " + version
                + ", status=" + status + "}";
    }
}
