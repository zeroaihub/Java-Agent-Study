package com.zero.ai.agentstudy.day15knowledgeplatform.workspace;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存工作空间仓储。
 *
 * <p><b>key 的设计值得注意</b>：这里用 {@code tenantId + ":" + workspaceId} 作为复合键，
 * 而不是只用 workspaceId。这不是多此一举——
 * 它让「跨租户访问」在<b>存储层就不可能发生</b>：
 * 即使上层代码漏了租户校验，用 A 租户的上下文去查 B 租户的空间 ID，
 * 拼出来的 key 在表里也根本不存在。
 * <b>把安全约束下沉到数据结构，比依赖开发者的自觉可靠得多。</b></p>
 *
 * @author ZeroAi
 */
public class InMemoryWorkspaceRepository implements WorkspaceRepository {

    private final Map<String, Workspace> store = new ConcurrentHashMap<>();

    @Override
    public Workspace save(Workspace workspace) {
        com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException
                .requireNonNull(workspace, "workspace");
        store.put(key(workspace.tenantId(), workspace.id()), workspace);
        return workspace;
    }

    @Override
    public Optional<Workspace> findById(TenantId tenantId, WorkspaceId workspaceId) {
        return Optional.ofNullable(store.get(key(tenantId, workspaceId)));
    }

    @Override
    public List<Workspace> findByTenant(TenantId tenantId, boolean includeArchived) {
        String prefix = tenantId.value() + ":";
        List<Workspace> out = new ArrayList<>();
        for (Map.Entry<String, Workspace> e : store.entrySet()) {
            if (e.getKey().startsWith(prefix)
                    && (includeArchived || !e.getValue().isArchived())) {
                out.add(e.getValue());
            }
        }
        return List.copyOf(out);
    }

    @Override
    public void delete(TenantId tenantId, WorkspaceId workspaceId) {
        store.remove(key(tenantId, workspaceId));
    }

    private static String key(TenantId tenantId, WorkspaceId workspaceId) {
        return tenantId.value() + ":" + workspaceId.value();
    }
}
