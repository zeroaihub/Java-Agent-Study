package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

/**
 * 审批状态：审批状态机的"节点"。
 *
 * <p>所有状态流转必须经过 {@code ApprovalStateMachine} 校验，
 * 任何非法流转都会抛出异常，从根本上杜绝脏状态。</p>
 *
 * <pre>
 * 状态流转（合法边）：
 *   PENDING --approve--> APPROVED            （单级通过 / 多级中间通过）
 *   PENDING --reject---> REJECTED
 *   PENDING --modify---> MODIFIED
 *   PENDING --timeout--> TIMEOUT
 *   PENDING --abort----> ABORTED
 *   APPROVED --nextLevel--> PENDING          （多级会签：进入下一级）
 *   APPROVED --finalize---> FINAL_APPROVED    （多级会签：最后一级过）
 *   MODIFIED --resubmit---> PENDING           （人工修改后重新提交审批）
 * </pre>
 */
public enum ApprovalStatus {

    /** 等待审批（初始状态，也是多级会签每一级的等待状态）。 */
    PENDING(false),

    /** 已批准（单级的终态；多级会签下是"当前级通过"的中间态）。 */
    APPROVED(false),

    /** 多级会签全部通过（真正的终态）。 */
    FINAL_APPROVED(true),

    /** 已驳回（终态）。 */
    REJECTED(true),

    /** 人工修改后待重新提交（非终态，会 resubmit 回到 PENDING）。 */
    MODIFIED(false),

    /** 审批超时（终态；具体后果由超时策略决定：拒绝或升级）。 */
    TIMEOUT(true),

    /** 主动终止（终态）。 */
    ABORTED(true);

    /** 是否为终态：终态不允许再发生任何流转。 */
    private final boolean terminal;

    ApprovalStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /**
     * 是否为终态。
     *
     * @return true 表示不可再流转
     */
    public boolean isTerminal() {
        return terminal;
    }
}