package com.zero.ai.agentstudy.day13officeagent.officecore.domain.context;

/**
 * 租户上下文（TenantContext）值对象。
 *
 * <p>企业级 Office Agent 通常以多租户 SaaS 形态部署——不同企业客户共用一套服务，但数据、
 * 资源、计量、配置必须严格隔离。TenantContext 就是贯穿整条 Pipeline 的"隔离凭证"：文件存储
 * 按 {@code tenantId} 分目录/分桶、知识库按租户分库、模型调用按租户计量与限额。</p>
 *
 * <p>它是不可变值对象，随 {@link OfficeContext} 一起在阶段间传递，任何适配器都能据此做出
 * 正确的隔离决策，而无需从线程局部变量等隐式渠道获取，避免虚拟线程/结构化并发下的上下文丢失。</p>
 *
 * @param tenantId    租户唯一标识
 * @param tenantName  租户展示名
 * @param userId      发起本次任务的用户标识
 * @param plan        订阅套餐，用于配额与功能开关
 * @author zero
 */
public record TenantContext(String tenantId, String tenantName, String userId, Plan plan) {

    public TenantContext {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        tenantName = tenantName == null ? tenantId : tenantName;
        userId = userId == null ? "anonymous" : userId;
        plan = plan == null ? Plan.FREE : plan;
    }

    /**
     * 快捷工厂：仅指定租户与用户，默认 FREE 套餐。
     *
     * @param tenantId 租户标识
     * @param userId   用户标识
     * @return 租户上下文
     */
    public static TenantContext of(String tenantId, String userId) {
        return new TenantContext(tenantId, tenantId, userId, Plan.FREE);
    }

    /** 是否为付费套餐。 */
    public boolean isPaid() {
        return plan != Plan.FREE;
    }

    /**
     * 订阅套餐枚举，决定配额与可用能力。
     */
    public enum Plan {
        /** 免费版：基础格式，受限配额。 */
        FREE,
        /** 专业版：全格式，较高配额。 */
        PRO,
        /** 企业版：全能力、私有部署、无限配额。 */
        ENTERPRISE
    }
}