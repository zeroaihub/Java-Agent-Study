package com.zero.ai.agentstudy.day15knowledgeplatform.workspace;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.Workspace;

import java.util.List;
import java.util.Optional;

/**
 * 工作空间仓储端口。
 *
 * @author ZeroAi
 */
public interface WorkspaceRepository {

    Workspace save(Workspace workspace);

    /**
     * 按 ID 查找。<b>必须带租户条件</b>，防止跨租户读取。
     */
    Optional<Workspace> findById(TenantId tenantId, WorkspaceId workspaceId);

    List<Workspace> findByTenant(TenantId tenantId, boolean includeArchived);

    void delete(TenantId tenantId, WorkspaceId workspaceId);
}
