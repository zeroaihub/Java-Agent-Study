package com.zero.ai.agentstudy.day16knowledgeagentv2.tenant;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

/**
 * 多租户隔离守卫（Chapter 15）。
 *
 * <p>SaaS 场景下，一切数据都归属某个租户。最严重的安全事故不是「查不到」，而是
 * 「查到了别家租户的数据」。TenantGuard 把「每次数据访问都必须校验租户归属」这条铁律
 * 沉淀成一个前置守卫：</p>
 * <ul>
 *     <li><b>强制带租户</b>：上下文缺租户直接拒绝（TENANT_REQUIRED）；</li>
 *     <li><b>禁止跨租户</b>：被访问资源的租户与上下文租户不一致即拒绝（TENANT_MISMATCH）。</li>
 * </ul>
 *
 * <p>该守卫应放在所有存储访问的最前面（检索、读写、缓存 key 拼装之前）。</p>
 *
 * @author ZeroAi
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    /**
     * 校验上下文携带了合法租户，返回该租户。
     */
    public static TenantId requireTenant(AgentContext ctx) {
        AgentException.requireNonNull(ctx, "ctx");
        TenantId tenant = ctx.tenantId();
        AgentException.requireState(tenant != null,
                AgentErrorCode.TENANT_REQUIRED, "请求缺少租户上下文");
        return tenant;
    }

    /**
     * 校验被访问资源属于当前上下文租户，禁止跨租户。
     *
     * @param ctx            请求上下文
     * @param resourceTenant 被访问资源所属租户
     */
    public static void assertSameTenant(AgentContext ctx, TenantId resourceTenant) {
        TenantId current = requireTenant(ctx);
        AgentException.requireNonNull(resourceTenant, "resourceTenant");
        AgentException.requireState(current.equals(resourceTenant),
                AgentErrorCode.TENANT_MISMATCH,
                "禁止跨租户访问：当前=" + current.value() + "，资源=" + resourceTenant.value());
    }

    /**
     * 为租户拼装隔离前缀，用于缓存 key / 索引名等，从物理上隔离不同租户的数据空间。
     */
    public static String scopedKey(AgentContext ctx, String rawKey) {
        TenantId tenant = requireTenant(ctx);
        AgentException.requireText(rawKey, "rawKey");
        return tenant.value() + ":" + rawKey;
    }
}