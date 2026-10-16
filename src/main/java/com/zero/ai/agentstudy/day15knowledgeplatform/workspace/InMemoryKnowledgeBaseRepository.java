package com.zero.ai.agentstudy.day15knowledgeplatform.workspace;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day15knowledgeplatform.workspace.model.KnowledgeBase;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存知识库仓储（复合键设计与 {@link InMemoryWorkspaceRepository} 一致）。
 *
 * @author ZeroAi
 */
public class InMemoryKnowledgeBaseRepository implements KnowledgeBaseRepository {

    private final Map<String, KnowledgeBase> store = new ConcurrentHashMap<>();

    @Override
    public KnowledgeBase save(KnowledgeBase knowledgeBase) {
        KnowledgeException.requireNonNull(knowledgeBase, "knowledgeBase");
        store.put(key(knowledgeBase.tenantId(), knowledgeBase.id()), knowledgeBase);
        return knowledgeBase;
    }

    @Override
    public Optional<KnowledgeBase> findById(TenantId tenantId, KnowledgeBaseId knowledgeBaseId) {
        return Optional.ofNullable(store.get(key(tenantId, knowledgeBaseId)));
    }

    @Override
    public List<KnowledgeBase> findByWorkspace(TenantId tenantId, WorkspaceId workspaceId) {
        List<KnowledgeBase> out = new ArrayList<>();
        for (KnowledgeBase kb : store.values()) {
            if (kb.tenantId().equals(tenantId) && kb.workspaceId().equals(workspaceId)) {
                out.add(kb);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 批量校验知识库是否存在且属于该租户。
     *
     * <p><b>为什么要在检索前做这一步？</b>
     * 因为调用方传来的知识库 ID 列表可能有三种问题：不存在、已删除、<b>属于别的租户</b>。
     * 如果不做校验直接拿去查，最坏的情况是——
     * 虽然向量库里按 tenantId 过滤了，查不到别人的数据，
     * 但攻击者可以通过枚举 ID 来<b>探测哪些 ID 存在</b>（存在性泄漏）。
     * 显式过滤 + 对不存在的 ID 直接拒绝，既安全又清晰。</p>
     */
    @Override
    public Set<KnowledgeBaseId> filterExisting(TenantId tenantId, Set<KnowledgeBaseId> ids) {
        Set<KnowledgeBaseId> existing = new LinkedHashSet<>();
        if (ids == null) {
            return existing;
        }
        for (KnowledgeBaseId id : ids) {
            if (store.containsKey(key(tenantId, id))) {
                existing.add(id);
            }
        }
        return existing;
    }

    @Override
    public void delete(TenantId tenantId, KnowledgeBaseId knowledgeBaseId) {
        store.remove(key(tenantId, knowledgeBaseId));
    }

    private static String key(TenantId tenantId, KnowledgeBaseId knowledgeBaseId) {
        return tenantId.value() + ":" + knowledgeBaseId.value();
    }
}
