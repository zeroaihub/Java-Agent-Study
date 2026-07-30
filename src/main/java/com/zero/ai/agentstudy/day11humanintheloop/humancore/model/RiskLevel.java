package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

/**
 * 风险等级：决定一个动作是否需要人工介入，以及需要几级审批。
 *
 * <p>设计原因：把"要不要人"这件事收敛成一个有限枚举，
 * 让 {@code RiskPolicy}（判定）与 {@code ApprovalGate}（拦截）之间只通过枚举通信，
 * 而不是散落的布尔标志或魔法字符串，便于扩展新的等级（例如 CRITICAL）。</p>
 */
public enum RiskLevel {

    /** 无风险：直接放行，不需要任何人工审批。 */
    NONE,

    /** 低风险：单级审批即可，或在启用反馈学习后可自动放行。 */
    LOW,

    /** 高风险：必须人工审批，通常触发多级会签。 */
    HIGH;

    /**
     * 是否需要人工审批。
     *
     * @return 只要不是 {@link #NONE} 就需要人工介入
     */
    public boolean requiresApproval() {
        return this != NONE;
    }
}