package com.zero.ai.agentstudy.day10planningagent.engine;

import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;

import java.util.List;
import java.util.Optional;

/**
 * 调度器：决定下一步执行哪个步骤（依赖满足 + 优先级排序），并做环检测。
 */
public interface Scheduler {

    /** 选出下一个可执行步骤（依赖全部完成且自身待执行，按优先级最高优先）。 */
    Optional<PlanStep> nextStep(Plan plan);

    /** 所有就绪步骤（依赖已满足的待执行步骤），供并行执行使用。 */
    List<PlanStep> readySteps(Plan plan);

    /** 是否全部步骤已结算。 */
    boolean isAllDone(Plan plan);

    /** 校验计划无环，有环则抛异常（fail-fast）。 */
    void validateNoCycle(Plan plan);
}