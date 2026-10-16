package com.zero.ai.agentstudy.day15knowledgeplatform.acl;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.AccessControlList;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ACL 仓储端口。
 *
 * @author ZeroAi
 */
public interface AclRepository {

    AccessControlList save(AccessControlList acl);

    Optional<AccessControlList> findByKnowledgeBase(TenantId tenantId, KnowledgeBaseId kbId);

    /**
     * 批量查询（检索时的热路径）。
     *
     * <p>为什么需要批量方法？因为一次检索可能横跨几十个知识库，
     * 逐个查询就是 N 次数据库往返。批量查询把它压成 1 次，
     * 这在检索链路里是实打实的延迟优化。</p>
     *
     * @return 存在的 ACL，按知识库 ID 索引；不存在的不会出现在结果里
     */
    Map<KnowledgeBaseId, AccessControlList> findByKnowledgeBases(TenantId tenantId,
                                                                 Set<KnowledgeBaseId> kbIds);

    /**
     * 列出某租户的全部 ACL。
     *
     * <p><b>为什么这个方法必须存在，而不是"取出全部再在应用层按租户过滤"？</b>
     * 因为在百万知识库规模下，"取出全部"会直接 OOM。
     * 租户过滤必须由 {@code WHERE tenant_id = ?} 在数据库里完成，
     * 这是多租户系统的铁律——<b>任何"先全量取出再过滤"的写法都是错的，
     * 即使在小数据量时它能跑通。</b></p>
     */
    java.util.Collection<AccessControlList> findByTenant(TenantId tenantId);

    void delete(TenantId tenantId, KnowledgeBaseId kbId);
}
