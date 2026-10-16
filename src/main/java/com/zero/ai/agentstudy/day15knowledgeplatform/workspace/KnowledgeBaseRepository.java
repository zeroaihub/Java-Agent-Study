package com.zero.ai.agentstudy.day15knowledgeplatform.workspace;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.KnowledgeBase;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 知识库仓储端口。
 *
 * @author ZeroAi
 */
public interface KnowledgeBaseRepository {

    KnowledgeBase save(KnowledgeBase knowledgeBase);

    Optional<KnowledgeBase> findById(TenantId tenantId, KnowledgeBaseId knowledgeBaseId);

    List<KnowledgeBase> findByWorkspace(TenantId tenantId, WorkspaceId workspaceId);

    /**
     * 批量按 ID 查找（检索时确定作用域的高频操作）。
     */
    Set<KnowledgeBaseId> filterExisting(TenantId tenantId, Set<KnowledgeBaseId> ids);

    void delete(TenantId tenantId, KnowledgeBaseId knowledgeBaseId);
}
