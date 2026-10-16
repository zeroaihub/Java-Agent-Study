package com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;

import java.time.Instant;

/**
 * 工作空间聚合根。
 *
 * <p><b>为什么需要 Workspace 这一层？</b>
 * 因为「租户」的粒度太粗了。一家公司（Tenant）内部，
 * 研发、法务、财务、客服各自的知识是完全不同的世界——
 * 它们不该互相出现在对方的检索结果里，哪怕同属一家公司。</p>
 *
 * <p>这就是 Confluence 的 Space、Notion 的 Teamspace 存在的原因。
 * <b>知识天然是「分域」的，而 Workspace 就是对「域」的建模。</b></p>
 *
 * <p><b>一条重要的设计经验</b>：Workspace 是<b>权限的默认边界</b>。
 * 授权时绝大多数情况下是「把某人加入某个空间」，
 * 而不是「把某人加入某篇文档」。文档级的细粒度授权只在例外场景下才用
 * （比如一份仅限 3 人可见的并购合同）。
 * <b>先做好空间级授权，再按需支持文档级</b>，
 * 反过来做会让权限体系复杂到没人敢维护。</p>
 *
 * @author ZeroAi
 */
public final class Workspace {

    private final WorkspaceId id;
    private final TenantId tenantId;
    private final String name;
    private final Instant createdAt;

    private String description;
    private Metadata metadata;
    private boolean archived;

    private Workspace(WorkspaceId id, TenantId tenantId, String name, String description, Metadata metadata) {
        this.id = KnowledgeException.requireNonNull(id, "workspaceId");
        this.tenantId = KnowledgeException.requireNonNull(tenantId, "tenantId");
        this.name = KnowledgeException.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "工作空间名称不能为空");
        }
        this.description = description == null ? "" : description;
        this.metadata = metadata == null ? Metadata.empty() : metadata;
        this.createdAt = Instant.now();
        this.archived = false;
    }

    /**
     * 创建工作空间。
     */
    public static Workspace create(TenantId tenantId, String name, String description, Metadata metadata) {
        return new Workspace(WorkspaceId.newId(), tenantId, name, description, metadata);
    }

    /**
     * 归属校验：跨租户访问直接拒绝。
     */
    public void assertBelongsTo(TenantId caller) {
        if (!this.tenantId.equals(caller)) {
            throw KnowledgeException.of(KnowledgeErrorCode.TENANT_MISMATCH,
                    "工作空间 " + id + " 不属于租户 " + caller);
        }
    }

    /**
     * 归档。
     *
     * <p><b>为什么是"归档"而不是"删除"？</b>
     * 因为知识是有价值的资产，直接删除等于销毁资产。
     * 归档后空间不再出现在默认检索范围内，但数据仍在，
     * 需要时可以恢复。这与文档的 DELETED 状态形成对比——
     * 文档删除往往是为了合规（数据必须真的消失），
     * 而空间归档通常只是"这个部门不用了"。</p>
     */
    public void archive() {
        if (archived) {
            throw KnowledgeException.of(KnowledgeErrorCode.OPERATION_NOT_ALLOWED,
                    "工作空间 " + id + " 已归档");
        }
        this.archived = true;
    }

    public void restore() {
        if (!archived) {
            throw KnowledgeException.of(KnowledgeErrorCode.OPERATION_NOT_ALLOWED,
                    "工作空间 " + id + " 未归档，无需恢复");
        }
        this.archived = false;
    }

    /**
     * 断言空间可用（未归档）。
     */
    public void requireActive() {
        if (archived) {
            throw KnowledgeException.of(KnowledgeErrorCode.WORKSPACE_NOT_FOUND,
                    "工作空间 " + id + " 已归档，不可用");
        }
    }

    public WorkspaceId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
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

    public boolean isArchived() {
        return archived;
    }

    @Override
    public String toString() {
        return "Workspace{" + id + ", name='" + name + "', archived=" + archived + "}";
    }
}
