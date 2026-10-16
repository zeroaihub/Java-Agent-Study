package com.zero.ai.agentstudy.day16knowledgeagentv2.tenant;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.Set;

/**
 * Chapter 15 演示：多租户隔离。
 *
 * <p>演示同租户放行、跨租户拒绝、以及租户隔离缓存 key 的拼装。</p>
 *
 * @author ZeroAi
 */
public class Chapter15Demo {

    public static void main(String[] args) {
        AgentContext acme = AgentContext.of(
                TenantId.of("acme"),
                WorkspaceId.of("ws-1"),
                UserId.of("u-1001"),
                Set.of("EMPLOYEE"));

        System.out.println("=== 同租户访问（放行）===");
        TenantGuard.assertSameTenant(acme, TenantId.of("acme"));
        System.out.println("  OK：acme 用户访问 acme 资源");

        System.out.println("\n=== 缓存 key 隔离 ===");
        System.out.println("  " + TenantGuard.scopedKey(acme, "kb:hr:query:年假"));

        System.out.println("\n=== 跨租户访问（拒绝）===");
        try {
            TenantGuard.assertSameTenant(acme, TenantId.of("globex"));
            System.out.println("  不应到达此处");
        } catch (AgentException e) {
            System.out.println("  已拦截 [" + e.getErrorCode().code() + "] " + e.getMessage());
        }
    }
}