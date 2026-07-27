package com.zero.ai.agentstudy.day10planningagent.reflection;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;

/**
 * 反思器（策略接口）。
 * 在每步执行后被主循环调用，根据「上下文 + 当前步骤 + 执行结果」产出裁决。
 * 这是 Planning Agent 自我纠错（Self-Correction）能力的核心抽象。
 */
public interface Reflector {

    /**
     * 观察并裁决。
     *
     * @param ctx    当前规划上下文（可读取历史观察、预算、已完成摘要）
     * @param step   刚执行的步骤
     * @param result 执行结果
     * @return 反思结论（裁决 + 理由）
     */
    Reflection reflect(PlanningContext ctx, PlanStep step, StepResult result);
}