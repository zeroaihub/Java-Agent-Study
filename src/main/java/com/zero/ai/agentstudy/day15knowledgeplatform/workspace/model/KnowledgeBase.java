package com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;

import java.time.Instant;

/**
 * 知识库聚合根 —— <b>检索的作用域</b>。
 *
 * <p>它是最容易被低估的一个概念。很多人做 RAG 时会跳过「知识库」这层，
 * 直接让所有文档躺在一个大池子里检索，理由是"反正都能搜到"。
 * 这在企业环境里会立刻出问题：</p>
 *
 * <ol>
 *     <li><b>无法做权限</b>：权限必须挂在某个实体上。没有知识库这层，
 *          权限就只能挂到文档级，100 万篇文档 = 100 万条 ACL 记录，
 *          检索时逐条判断，性能崩溃，维护更是灾难。</li>
 *     <li><b>无法做配额与计费</b>：SaaS 按"文档量/知识库数"收费，没有这层就没法计价。</li>
 *     <li><b>无法隔离质量</b>：产品文档和聊天记录混在一个库里，
 *          检索质量会被低质量数据拖垮，而你可能根本不知道是哪些数据在拖后腿。</li>
 *     <li><b>无法按场景调优</b>：技术文档适合小切片 + 结构化策略，
 *          FAQ 适合整段切片。混在一个库里，只能取一个都不合适的折中参数。</li>
 * </ol>
 *
 * <p><b>所以：知识库不是"分组文件夹"，而是检索、权限、计费、调优的共同边界。</b></p>
 *
 * <p><b>embeddingModel 字段的作用</b>：记录该库当前使用的向量模型。
 * 更换模型时，可以先新建一个库、双写、验证、再切换——
 * 这就是"向量重建"的安全姿势，避免"删掉重灌"期间服务不可用。</p>
 *
 * @author ZeroAi
 */
public final class KnowledgeBase {

    private final KnowledgeBaseId id;
    private final TenantId tenantId;
    private final WorkspaceId workspaceId;
    private final String name;
    private final Instant createdAt;

    private String description;
    private Metadata metadata;
    /** 当前库内向量所用的模型标识。空表示尚未写入任何向量。 */
    private String embeddingModel;
    private int documentCount;

    private KnowledgeBase(KnowledgeBaseId id, TenantId tenantId, WorkspaceId workspaceId,
                          String name, String description, Metadata metadata) {
        this.id = KnowledgeException.requireNonNull(id, "knowledgeBaseId");
        this.tenantId = KnowledgeException.requireNonNull(tenantId, "tenantId");
        this.workspaceId = KnowledgeException.requireNonNull(workspaceId, "workspaceId");
        this.name = KnowledgeException.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "知识库名称不能为空");
        }
        this.description = description == null ? "" : description;
        this.metadata = metadata == null ? Metadata.empty() : metadata;
        this.createdAt = Instant.now();
        this.embeddingModel = null;
        this.documentCount = 0;
    }

    public static KnowledgeBase create(TenantId tenantId, WorkspaceId workspaceId,
                                       String name, String description, Metadata metadata) {
        return new KnowledgeBase(KnowledgeBaseId.newId(), tenantId, workspaceId,
                name, description, metadata);
    }

    public void assertBelongsTo(TenantId caller) {
        if (!this.tenantId.equals(caller)) {
            throw KnowledgeException.of(KnowledgeErrorCode.TENANT_MISMATCH,
                    "知识库 " + id + " 不属于租户 " + caller);
        }
    }

    /**
     * 记录一次向量写入，并校验模型一致性。
     *
     * <p><b>这是「Embedding 模型锁版本」这条铁律的落地位置。</b>
     * 库级记录当前模型，每次写入前比对：不一致就拒绝写入，
     * 并提示必须全量重建——而不是让新旧向量悄悄混在一起。</p>
     */
    public void recordEmbedding(String model, int writtenCount) {
        KnowledgeException.requireNonNull(model, "embeddingModel");
        if (this.embeddingModel != null && !this.embeddingModel.equals(model)) {
            throw new KnowledgeException(KnowledgeErrorCode.EMBEDDING_FAILED,
                    "知识库 " + id + " 已使用模型 " + this.embeddingModel
                            + "，无法写入 " + model + " 产生的向量。"
                            + "更换 Embedding 模型必须全量重建向量（先建新库 → 双写 → 验证 → 切换）");
        }
        this.embeddingModel = model;
        this.documentCount += writtenCount;
    }

    /**
     * 清空模型标记（用于全量重建：先清库，再重新灌入）。
     */
    public void resetEmbeddingModel() {
        this.embeddingModel = null;
        this.documentCount = 0;
    }

    public KnowledgeBaseId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Metadata metadata() {
        return metadata;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    public int documentCount() {
        return documentCount;
    }

    @Override
    public String toString() {
        return "KnowledgeBase{" + id + ", name='" + name + "', model=" + embeddingModel
                + ", docs=" + documentCount + "}";
    }
}
