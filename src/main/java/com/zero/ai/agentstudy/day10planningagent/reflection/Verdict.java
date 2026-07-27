package com.zero.ai.agentstudy.day10planningagent.reflection;

/**
 * 反思裁决：反思器观察一步执行结果后，给出下一步动作指令。
 * 主循环（PlanningService）据此决定继续、重试、重规划还是中止。
 */
public enum Verdict {

    /** 步骤成功，按计划推进到下一个就绪步骤。 */
    CONTINUE,

    /** 步骤失败但仍可挽救（未超重试上限），重置该步骤重新执行。 */
    RETRY_STEP,

    /** 计划本身有问题（工具选错、路径行不通），触发增量重规划。 */
    REPLAN,

    /** 无法恢复（预算耗尽、致命错误），中止任务并标记失败。 */
    ABORT
}