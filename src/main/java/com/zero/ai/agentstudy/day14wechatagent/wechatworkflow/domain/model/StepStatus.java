package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model;

/**
 * 工作流单步（Step）的生命周期状态。
 *
 * <p>一个步骤从 {@link #PENDING} 开始，被引擎选中后进入 {@link #RUNNING}，
 * 最终落到 {@link #SUCCEEDED}、{@link #FAILED}、{@link #SKIPPED} 或
 * {@link #WAITING_APPROVAL} 之一。状态是工作流引擎调度与恢复的依据。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public enum StepStatus {

    /** 待执行：已规划但尚未开始。 */
    PENDING,

    /** 执行中：引擎正在运行该步骤。 */
    RUNNING,

    /** 成功：步骤已正常完成。 */
    SUCCEEDED,

    /** 失败：步骤执行抛错且重试已耗尽。 */
    FAILED,

    /** 跳过:条件分支判定为不需执行。 */
    SKIPPED,

    /** 等待人工审批:需 Human-in-the-Loop 放行后方可继续。 */
    WAITING_APPROVAL;

    /** @return 是否为终态（不会再被调度）。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == SKIPPED;
    }
}