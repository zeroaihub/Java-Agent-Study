package com.zero.ai.agentstudy.day15knowledgeplatform.core.tenant;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

/**
 * 租户上下文（多租户核心基础设施）。
 *
 * <p>在请求入口（Filter / Interceptor）解析出租户后放入本上下文，
 * 后续所有业务逻辑、仓储查询都从这里获取当前租户，实现「一切带租户」。</p>
 *
 * <p>实现说明：使用 {@link ThreadLocal}。在 Java 21 虚拟线程模型下，
 * 每个请求跑在独立的虚拟线程上，ThreadLocal 天然隔离，无需担心串号。
 * 唯一要注意的是「用完必须 clear」，防止线程复用导致的租户泄漏——
 * 这也是我们提供 {@link #runWithTenant} 模板方法的原因。</p>
 *
 * @author ZeroAi
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 设置当前租户。
     */
    public static void set(TenantId tenantId) {
        KnowledgeException.requireNonNull(tenantId, "tenantId");
        CURRENT.set(tenantId);
    }

    /**
     * 获取当前租户；若未设置则抛出 TENANT_REQUIRED。
     */
    public static TenantId require() {
        TenantId tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new KnowledgeException(KnowledgeErrorCode.TENANT_REQUIRED);
        }
        return tenantId;
    }

    /**
     * 获取当前租户（可能为 null，供内部判断使用）。
     */
    public static TenantId getOrNull() {
        return CURRENT.get();
    }

    /**
     * 清除当前租户（请求结束必须调用，防止租户泄漏）。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 在指定租户上下文中执行一段逻辑，自动设置并清理。
     * 推荐在异步任务、后台任务、测试中使用，杜绝忘记 clear。
     */
    public static <T> T runWithTenant(TenantId tenantId, java.util.function.Supplier<T> action) {
        TenantId previous = CURRENT.get();
        try {
            set(tenantId);
            return action.get();
        } finally {
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
    }
}