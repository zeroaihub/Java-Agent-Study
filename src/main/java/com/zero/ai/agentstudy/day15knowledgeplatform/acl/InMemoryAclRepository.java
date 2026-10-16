package com.zero.ai.agentstudy.day15knowledgeplatform.acl;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.AccessControlList;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 ACL 仓储。
 *
 * <p>复合键 {@code tenantId:kbId}，与其他仓储保持同一约定：
 * <b>让跨租户访问在存储层就不可能发生。</b></p>
 *
 * @author ZeroAi
 */
public class InMemoryAclRepository implements AclRepository {

    private final Map<String, AccessControlList> store = new ConcurrentHashMap<>();

    @Override
    public AccessControlList save(AccessControlList acl) {
        KnowledgeException.requireNonNull(acl, "acl");
        store.put(key(acl.tenantId(), acl.knowledgeBaseId()), acl);
        return acl;
    }

    @Override
    public Optional<AccessControlList> findByKnowledgeBase(TenantId tenantId, KnowledgeBaseId kbId) {
        return Optional.ofNullable(store.get(key(tenantId, kbId)));
    }

    @Override
    public Map<KnowledgeBaseId, AccessControlList> findByKnowledgeBases(TenantId tenantId,
                                                                        Set<KnowledgeBaseId> kbIds) {
        Map<KnowledgeBaseId, AccessControlList> out = new LinkedHashMap<>();
        if (kbIds == null) {
            return out;
        }
        for (KnowledgeBaseId id : kbIds) {
            AccessControlList acl = store.get(key(tenantId, id));
            if (acl != null) {
                out.put(id, acl);
            }
        }
        return out;
    }

    @Override
    public java.util.Collection<AccessControlList> findByTenant(TenantId tenantId) {
        String prefix = tenantId.value() + ":";
        java.util.List<AccessControlList> out = new java.util.ArrayList<>();
        for (Map.Entry<String, AccessControlList> e : store.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.add(e.getValue());
            }
        }
        return java.util.List.copyOf(out);
    }

    @Override
    public void delete(TenantId tenantId, KnowledgeBaseId kbId) {
        store.remove(key(tenantId, kbId));
    }

    private static String key(TenantId tenantId, KnowledgeBaseId kbId) {
        return tenantId.value() + ":" + kbId.value();
    }
}
