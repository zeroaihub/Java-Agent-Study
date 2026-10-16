package com.zero.ai.agentstudy.day15knowledgeplatform.acl;

import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.AccessControlList;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Permission;
import com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Principal;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.KnowledgeBaseId;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 权限应用服务 —— <b>权限感知检索（Permission-aware Search）</b> 的核心。
 *
 * <p><b>本模块要解决的头号问题：权限过滤必须发生在召回之前。</b></p>
 *
 * <p>这个结论来自 Confluence AI 的设计启示，也是 Glean 的核心竞争力所在。
 * 为什么必须前置？因为向量检索（尤其是 HNSW 这类近似索引）返回的是
 * <b>全局最近的 K 条</b>。假设用户只有 1 个知识库的权限，而全库有 100 个，
 * 那么全局 Top-50 里可能只有 0~1 条是他有权看的。</p>
 *
 * <p>后置过滤的后果是灾难性的三重损失：</p>
 * <ol>
 *     <li><b>召回率凭空归零</b>：正确的那条排在第 51 名，永远拿不到；</li>
 *     <li><b>安全边界被突破</b>：无权数据已经被读进应用内存，
 *         日志、异常栈、序列化缓存任何一处疏忽都会泄漏；</li>
 *     <li><b>性能浪费</b>：算了 50 条相似度，扔掉 49 条。</li>
 * </ol>
 *
 * <p>所以正确做法是 {@link #resolveReadableScope}：
 * <b>先算出「这个主体能读哪些知识库」，把这个集合作为过滤条件下推给检索层</b>，
 * 让检索只在有权限的集合里找。</p>
 *
 * @author ZeroAi
 */
public class AclService {

    private final AclRepository aclRepository;

    public AclService(AclRepository aclRepository) {
        this.aclRepository = KnowledgeException.requireNonNull(aclRepository, "aclRepository");
    }

    /**
     * 单点鉴权：主体是否对某知识库拥有某项权限。
     */
    public boolean hasPermission(Principal principal, KnowledgeBaseId kbId, Permission permission) {
        KnowledgeException.requireNonNull(principal, "principal");
        KnowledgeException.requireNonNull(kbId, "kbId");
        KnowledgeException.requireNonNull(permission, "permission");

        AccessControlList acl = aclRepository.findByKnowledgeBase(principal.tenantId(), kbId)
                .orElse(null);
        // 没有 ACL 记录 = 默认拒绝
        if (acl == null) {
            return false;
        }
        return acl.hasPermission(principal, permission);
    }

    /**
     * 断言有权限，否则抛 ACCESS_DENIED（fail-fast）。
     */
    public void requirePermission(Principal principal, KnowledgeBaseId kbId, Permission permission) {
        if (!hasPermission(principal, kbId, permission)) {
            throw KnowledgeException.of(KnowledgeErrorCode.ACCESS_DENIED,
                    "主体 " + principal.userId() + " 无权对知识库 " + kbId + " 执行 " + permission);
        }
    }

    /**
     * <b>权限感知检索的入口方法</b>：计算主体在指定候选范围内的可读知识库集合。
     *
     * <p>处理逻辑：</p>
     * <ol>
     *     <li>候选为空 → 返回租户下<b>全部</b>可读知识库（"不限知识库"场景）；
     *         <b>注意这里不是返回全部知识库，而是全部"可读的"知识库</b>——
     *         这正是权限前置的体现；</li>
     *     <li>候选非空 → 在候选范围内逐一鉴权，只保留有 SEARCH 权限的。</li>
     * </ol>
     *
     * <p><b>一个刻意的设计：无权访问时返回空集合，而不是抛异常。</b>
     * 因为这个方法被用于检索链路，抛异常会让"没有权限"变成一次 500 错误，
     * 而它本质上是一个正常的业务结果：查不到任何东西。
     * 上层可以在需要时把"空结果"包装成友好的提示。</p>
     *
     * @param principal      访问主体
     * @param candidateKbIds 候选知识库；为空表示不限
     * @return 该主体可读的知识库 ID 集合
     */
    public Set<KnowledgeBaseId> resolveReadableScope(Principal principal,
                                                     Set<KnowledgeBaseId> candidateKbIds) {
        KnowledgeException.requireNonNull(principal, "principal");
        TenantId tenantId = principal.tenantId();

        Set<KnowledgeBaseId> readable = new LinkedHashSet<>();

        if (candidateKbIds == null || candidateKbIds.isEmpty()) {
            // 不限知识库：遍历该租户下的全部 ACL，挑出可读的
            for (AccessControlList acl : aclRepository.findByTenant(tenantId)) {
                if (acl.hasPermission(principal, Permission.SEARCH)) {
                    readable.add(acl.knowledgeBaseId());
                }
            }
            return readable;
        }

        Map<KnowledgeBaseId, AccessControlList> acls =
                aclRepository.findByKnowledgeBases(tenantId, candidateKbIds);
        for (KnowledgeBaseId kbId : candidateKbIds) {
            AccessControlList acl = acls.get(kbId);
            if (acl != null && acl.hasPermission(principal, Permission.SEARCH)) {
                readable.add(kbId);
            }
        }
        return readable;
    }

    // ---------------- 授权管理 ----------------

    public AccessControlList grant(Principal operator, KnowledgeBaseId kbId,
                                   String targetUser, String targetGroup,
                                   com.zero.ai.agentstudy.day15knowledgeplatform.acl.model.Role role) {
        requirePermission(operator, kbId, Permission.MANAGE);
        AccessControlList acl = aclRepository.findByKnowledgeBase(operator.tenantId(), kbId)
                .orElseGet(() -> AccessControlList.builder(operator.tenantId(), kbId).build());

        AccessControlList updated = acl;
        if (targetUser != null && !targetUser.isBlank()) {
            updated = updated.grantUser(targetUser, role);
        }
        if (targetGroup != null && !targetGroup.isBlank()) {
            updated = updated.grantGroup(targetGroup, role);
        }
        return aclRepository.save(updated);
    }
}
