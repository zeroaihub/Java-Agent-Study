package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model;

/**
 * 工作流任务（WorkflowTask）整体的生命周期状态。
 *
 * <p>与 {@link StepStatus} 不同，本枚举描述的是「整个多步骤任务」的宏观状态：
 * 从 {@link #CREATED} 规划完成后进入 {@link #RUNNING}，可能因需要人工审批而
 * 挂起为 {@link #SUSPENDED}，最终以 {@link #COMPLETED} 或 {@link #FAILED} 收尾。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public enum WorkflowStatus {

    /** 已创建：任务与步骤已规划，尚未开始执行。 */
    CREATED,

    /** 执行中：引擎正在逐步推进。 */
    RUNNING,

    /** 已挂起：遇到人工审批步骤，等待外部放行后恢复。 */
    SUSPENDED,

    /** 已完成：所有步骤成功（或按条件跳过）。 */
    COMPLETED,

    /** 已失败:某关键步骤失败且无法继续。 */
    FAILED;

    /** @return 是否为终态。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /** @return 是否可继续推进（非终态、非挂起）。 */
    public boolean isRunnable() {
        return this == CREATED || this == RUNNING;
    }
}