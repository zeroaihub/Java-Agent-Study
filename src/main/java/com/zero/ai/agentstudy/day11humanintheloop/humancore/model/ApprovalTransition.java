package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

/**
 * 审批流转动作：审批状态机的"边"。
 *
 * <p>把"能做什么操作"也建模成枚举，配合 {@link ApprovalStatus}（节点）
 * 就能用一张 (状态, 动作) -> 目标状态 的表来完整描述状态机，
 * 避免在业务代码里写满 if/switch。</p>
 */
public enum ApprovalTransition {

    /** 批准当前级。 */
    APPROVE,

    /** 驳回。 */
    REJECT,

    /** 人工修改（进入 MODIFIED，等待重新提交）。 */
    MODIFY,

    /** 超时。 */
    TIMEOUT,

    /** 主动终止。 */
    ABORT,

    /** 多级会签：进入下一级（从 APPROVED 回到 PENDING）。 */
    NEXT_LEVEL,

    /** 多级会签：最后一级通过（从 APPROVED 到 FINAL_APPROVED）。 */
    FINALIZE,

    /** 人工修改后重新提交（从 MODIFIED 回到 PENDING）。 */
    RESUBMIT
}