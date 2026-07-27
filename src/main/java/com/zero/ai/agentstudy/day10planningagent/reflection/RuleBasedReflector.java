package com.zero.ai.agentstudy.day10planningagent.reflection;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 基于规则的反思器（确定性、零 LLM 成本、可预测）。
 *
 * <p>决策规则：
 * <ul>
 *   <li>成功 -> CONTINUE</li>
 *   <li>失败且该步尝试次数未超上限 -> RETRY_STEP（重试当前步骤）</li>
 *   <li>失败且已超重试上限，但重规划护栏仍有余量 -> REPLAN（换条路走）</li>
 *   <li>失败且重规划护栏也耗尽 -> ABORT（放弃）</li>
 * </ul>
 *
 * <p>作为 {@link LlmReflector} 的降级兜底：当 LLM 反思异常时回退到本实现。
 */
@Component
public class RuleBasedReflector implements Reflector {

    /** 单步最大重试次数（含首次执行外的重试次数）。 */
    private final int maxRetryPerStep;

    public RuleBasedReflector(
            @Value("${zero.planning.max-retry-per-step:2}") int maxRetryPerStep) {
        this.maxRetryPerStep = maxRetryPerStep;
    }

    @Override
    public Reflection reflect(PlanningContext ctx, PlanStep step, StepResult result) {
        if (result.success()) {
            return Reflection.cont("步骤[" + step.id() + "]执行成功，推进下一步");
        }

        // 失败分支：先看是否还能重试当前步骤
        // attemptCount 已包含本次执行，故 attemptCount <= maxRetryPerStep 表示仍有重试机会
        if (step.attemptCount() <= maxRetryPerStep) {
            return Reflection.retry("步骤[" + step.id() + "]失败(" + brief(result.error())
                    + ")，第 " + step.attemptCount() + " 次尝试，未超重试上限 " + maxRetryPerStep + "，重试");
        }

        // 重试耗尽：看重规划护栏是否还有余量
        if (ctx.replanCount() < ctx.goal().maxReplan()) {
            return Reflection.replan("步骤[" + step.id() + "]重试耗尽，触发增量重规划");
        }

        return Reflection.abort("步骤[" + step.id() + "]失败且重试/重规划护栏均耗尽，中止任务");
    }

    private String brief(String error) {
        if (error == null) return "";
        return error.length() > 80 ? error.substring(0, 80) + "..." : error;
    }
}