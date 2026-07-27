package com.zero.ai.agentstudy.day10planningagent.core;

/**
 * 步骤生命周期状态。
 */
public enum StepStatus {
    /** 待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 成功完成 */
    DONE,
    /** 失败 */
    FAILED,
    /** 被跳过（如依赖失败或重规划弃用） */
    SKIPPED
}