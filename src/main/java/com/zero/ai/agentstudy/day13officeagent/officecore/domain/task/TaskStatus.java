package com.zero.ai.agentstudy.day13officeagent.officecore.domain.task;

import java.util.Set;

/**
 * 任务状态（TaskStatus）枚举——办公任务生命周期状态机。
 *
 * <p>一次 Office 任务不是"一把梭跑完"，而是一个受控的状态机：可能在审批处挂起等待人工，
 * 可能失败后重试，可能被取消。用枚举 + 合法迁移集合把状态流转约束在领域内，杜绝
 * "已完成的任务又被改回运行中"这类非法跃迁。</p>
 *
 * @author zero
 */
public enum TaskStatus {

/** 已创建，尚未开始执行。 */
    CREATED,
    /** 执行中，流水线正在推进。 */
    RUNNING,
    /** 挂起，等待人工审批（Human-in-the-loop）。 */
    WAITING_APPROVAL,
    /** 成功完成。 */
    COMPLETED,
    /** 执行失败。 */
    FAILED,
    /** 已取消。 */
    CANCELLED;

    /**
     * 判断能否合法迁移到目标状态。
     *
     * @param target 目标状态
     * @return 合法返回 {@code true}
     */
    public boolean canTransitionTo(TaskStatus target) {
        return switch (this) {
            case CREATED -> Set.of(RUNNING, CANCELLED).contains(target);
            case RUNNING -> Set.of(WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED).contains(target);
            case WAITING_APPROVAL -> Set.of(RUNNING, CANCELLED, FAILED).contains(target);
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    /** 是否为终态（不可再迁移）。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}