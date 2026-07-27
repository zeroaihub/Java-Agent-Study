package com.zero.ai.agentstudy.day10planningagent.core.planner;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.core.Plan;

/**
 * 规划器（动脑）：把目标拆解为可执行计划，并支持增量重规划。
 */
public interface Planner {

    /** 首次规划：目标 -> 计划。 */
    Plan plan(Goal goal);

    /**
     * 重规划：基于当前上下文（已完成成果 + 失败观察）产出新计划。
     * 实现应保留已完成步骤成果（增量修正），而非推倒重来。
     */
    Plan replan(PlanningContext ctx);
}