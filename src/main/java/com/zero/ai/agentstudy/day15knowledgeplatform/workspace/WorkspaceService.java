package com.zero.ai.agentstudy.day15knowledgeplatform.workspace;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.Metadata;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant.TenantContext;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.KnowledgeBase;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.Workspace;

import java.util.List;
import java.util.Set;

/**
 * 工作空间应用服务。
 *
 * <p><b>本类最重要的约定：所有方法的租户都从 {@link TenantContext} 取，
 * 不接受调用方传入 tenantId。</b></p>
 *
 * <p>这是一个容易被忽略但极其关键的安全设计。对比两种签名：</p>
 * <pre>
 *   // 危险：调用方可以传任意 tenantId
 *   KnowledgeBase create(TenantId tenantId, String name)
 *
 *   // 安全：租户只能由上下文决定
 *   KnowledgeBase create(String name)
 * </pre>
 *
 * <p>第一种签名意味着：任何一个 Controller 方法只要把请求参数里的 tenantId
 * 透传进来，就构成了一个越权漏洞。而这类漏洞在代码审查时极难发现——
 * 因为方法签名看起来"很合理"。
 * <b>把租户从参数列表里彻底移除，是从根源上消灭这类漏洞。</b></p>
 *
 * @author ZeroAi
 */
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            KnowledgeBaseRepository knowledgeBaseRepository) {
        this.workspaceRepository = KnowledgeException.requireNonNull(workspaceRepository, "workspaceRepository");
        this.knowledgeBaseRepository =
                KnowledgeException.requireNonNull(knowledgeBaseRepository, "knowledgeBaseRepository");
    }

    // ---------------- 工作空间 ----------------

    public Workspace createWorkspace(String name, String description, Metadata metadata) {
        TenantId tenantId = TenantContext.require();
        return workspaceRepository.save(Workspace.create(tenantId, name, description, metadata));
    }

    public Workspace getWorkspace(WorkspaceId workspaceId) {
        TenantId tenantId = TenantContext.require();
        Workspace ws = workspaceRepository.findById(tenantId, workspaceId)
                .orElseThrow(() -> KnowledgeException.of(KnowledgeErrorCode.WORKSPACE_NOT_FOUND,
                        "工作空间不存在：" + workspaceId));
        ws.assertBelongsTo(tenantId);
        return ws;
    }

    public List<Workspace> listWorkspaces(boolean includeArchived) {
        return workspaceRepository.findByTenant(TenantContext.require(), includeArchived);
    }

    public void archiveWorkspace(WorkspaceId workspaceId) {
        Workspace ws = getWorkspace(workspaceId);
        ws.archive();
        workspaceRepository.save(ws);
    }

    // ---------------- 知识库 ----------------

    public KnowledgeBase createKnowledgeBase(WorkspaceId workspaceId, String name,
                                             String description, Metadata metadata) {
        TenantId tenantId = TenantContext.require();
        // 先在同一个租户下确认工作空间存在且未归档，防止把知识库挂到不存在的空间上
        Workspace ws = getWorkspace(workspaceId);
        ws.requireActive();
        return knowledgeBaseRepository.save(
                KnowledgeBase.create(tenantId, ws.id(), name, description, metadata));
    }

    public KnowledgeBase getKnowledgeBase(KnowledgeBaseId kbId) {
        TenantId tenantId = TenantContext.require();
        KnowledgeBase kb = knowledgeBaseRepository.findById(tenantId, kbId)
                .orElseThrow(() -> KnowledgeException.of(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                        "知识库不存在：" + kbId));
        kb.assertBelongsTo(tenantId);
        return kb;
    }

    public List<KnowledgeBase> listKnowledgeBases(WorkspaceId workspaceId) {
        TenantId tenantId = TenantContext.require();
        getWorkspace(workspaceId).assertBelongsTo(tenantId);
        return knowledgeBaseRepository.findByWorkspace(tenantId, workspaceId);
    }

    /**
     * 解析检索作用域：把调用方传入的知识库 ID 收敛为「确实属于当前租户」的集合。
     *
     * <p>这是检索链路的第一道闸门。传入空集合表示「不限知识库」，
     * 此时仍会严格按租户隔离。</p>
     *
     * @param requestedKbIds 请求的知识库；为空表示不限
     * @return 校验通过的作用域集合（可能小于请求集合）
     */
    public Set<KnowledgeBaseId> resolveScope(Set<KnowledgeBaseId> requestedKbIds) {
        TenantId tenantId = TenantContext.require();
        if (requestedKbIds == null || requestedKbIds.isEmpty()) {
            return Set.of();
        }
        Set<KnowledgeBaseId> existing = knowledgeBaseRepository.filterExisting(tenantId, requestedKbIds);
        if (existing.isEmpty()) {
            throw KnowledgeException.of(KnowledgeErrorCode.KNOWLEDGE_BASE_NOT_FOUND,
                    "指定的知识库均不存在或不属于当前租户：" + requestedKbIds);
        }
        return existing;
    }
}
