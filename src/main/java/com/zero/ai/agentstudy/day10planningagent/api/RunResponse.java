package com.zero.ai.agentstudy.day10planningagent.api;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;

import java.util.List;

/**
 * 运行规划任务的出参。把内部上下文投影为对外可读的结构，避免暴露领域对象。
 *
 * @param state      终态（SUCCEEDED / FAILED）
 * @param success    是否成功
 * @param planId     计划 id
 * @param stepCount  实际执行步数
 * @param replanCount 重规划次数
 * @param steps      步骤明细
 * @param finalOutput 最终产出（最后一个完成步骤的输出）
 * @param traces     执行轨迹（阶段 | 详情）
 */
public record RunResponse(
        String state,
        boolean success,
        String planId,
        int stepCount,
        int replanCount,
        List<StepView> steps,
        String finalOutput,
        List<String> traces
) {

    /** 步骤视图。 */
    public record StepView(String id, String action, String tool, String status,
                           int attempts, String output, String error) {
    }

    /** 从上下文构建响应。 */
    public static RunResponse from(PlanningContext ctx) {
        var plan = ctx.plan();
        List<StepView> stepViews = (plan == null) ? List.of()
                : plan.steps().stream()
                    .map(RunResponse::toView)
                    .toList();

        String finalOutput = "";
        if (plan != null) {
            // 取最后一个 DONE 步骤的输出作为最终产出
            for (PlanStep s : plan.steps()) {
                if (s.isDone() && s.output() != null) {
                    finalOutput = s.output();
                }
            }
        }

        List<String> traces = ctx.traces().stream()
                .map(t -> t.phase() + " | " + t.detail())
                .toList();

        return new RunResponse(
                ctx.state().name(),
                ctx.state().name().equals("SUCCEEDED"),
                plan == null ? null : plan.id(),
                ctx.stepCount(),
                ctx.replanCount(),
                stepViews,
                finalOutput,
                traces
        );
    }

    private static StepView toView(PlanStep s) {
        return new StepView(s.id(), s.action(), s.suggestedTool(),
                s.status().name(), s.attemptCount(), s.output(), s.error());
    }
}