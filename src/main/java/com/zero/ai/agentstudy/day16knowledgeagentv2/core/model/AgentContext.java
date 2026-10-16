package com.zero.ai.agentstudy.day16knowledgeagentv2.core.model;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 请求上下文（不可变值对象）。
 *
 * <p>贯穿整条 Agent 推理链（Router → Query → Retrieval → Rerank → ACL →
 * Citation → Verifier）的「随身携带」上下文。它同时承载：</p>
 * <ul>
 *     <li><b>多租户隔离</b>：tenantId / workspaceId，是一切数据访问的前置约束；</li>
 *     <li><b>权限主体</b>：userId / roles，供 ACL 权限感知检索使用；</li>
 *     <li><b>链路追踪</b>：traceId，串起全链路 Observability。</li>
 * </ul>
 *
 * <p>设计为 record（不可变）：一旦创建不可篡改，可安全地在虚拟线程 / 异步任务间传递，
 * 避免「中途被改租户」导致的越权。</p>
 *
 * @param tenantId    租户
 * @param workspaceId 工作空间
 * @param userId      发起用户
 * @param roles       用户角色集合（RBAC）
 * @param traceId     全链路追踪 ID
 * @author ZeroAi
 */
public record AgentContext(
        TenantId tenantId,
        WorkspaceId workspaceId,
        UserId userId,
        Set<String> roles,
        String traceId
) implements Serializable {

    public AgentContext {
        AgentException.requireNonNull(tenantId, "tenantId");
        AgentException.requireNonNull(userId, "userId");
        // 角色可为空集合（游客/默认），但不允许 null
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        if (traceId == null || traceId.isBlank()) {
            traceId = "trace-" + UUID.randomUUID();
        }
    }

    /**
     * 便捷构建器：仅提供最小必要字段，自动生成 traceId。
     */
    public static AgentContext of(TenantId tenantId, WorkspaceId workspaceId, UserId userId, Set<String> roles) {
        return new AgentContext(tenantId, workspaceId, userId, roles, null);
    }

    /**
     * 判断是否具备指定角色（RBAC 快速判断）。
     */
    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    /**
     * 是否为管理员（可见全部知识，谨慎使用）。
     */
    public boolean isAdmin() {
        return roles.contains("ADMIN") || roles.contains("KNOWLEDGE_ADMIN");
    }

    /**
     * 返回只读角色视图，防止外部修改。
     */
    public Set<String> roles() {
        return Collections.unmodifiableSet(roles);
    }
}