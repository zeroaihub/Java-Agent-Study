package com.zero.ai.agentstudy.day11humanintheloop.interruptmanager;

/**
 * Agent 执行状态（Execution State）。
 *
 * <p>HITL 的核心能力之一是「让 Agent 在危险动作前停下来」。这就要求 Agent 的执行
 * 过程本身是有状态的、可被外部观测和控制的。本枚举描述一次 Agent 执行所处的宏观阶段：</p>
 *
 * <ul>
 *   <li>{@link #RUNNING}：正常执行中。</li>
 *   <li>{@link #INTERRUPTED}：被中断信号打断，执行现场已保存，等待人类决策。</li>
 *   <li>{@link #WAITING_APPROVAL}：因触发审批网关而挂起，等待审批结果。</li>
 *   <li>{@link #RESUMED}：审批通过后已恢复执行（瞬时态，很快回到 RUNNING）。</li>
 *   <li>{@link #COMPLETED}：执行正常结束（终态）。</li>
 *   <li>{@link #ABORTED}：被人为终止或审批驳回后放弃（终态）。</li>
 * </ul>
 *
 * <p>与审批状态 {@code ApprovalStatus} 的区别：审批状态描述「某个动作的审批走到哪」，
 * 而执行状态描述「整个 Agent 任务的执行走到哪」。一次执行里可能包含多次审批。</p>
 */
public enum ExecutionState {

    RUNNING(false),
    INTERRUPTED(false),
    WAITING_APPROVAL(false),
    RESUMED(false),
    COMPLETED(true),
    ABORTED(true);

    /** 是否终态（终态不可再流转）。 */
    private final boolean terminal;

    ExecutionState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** 是否处于「被挂起、等待人类」的状态。 */
    public boolean isSuspended() {
        return this == INTERRUPTED || this == WAITING_APPROVAL;
    }
}