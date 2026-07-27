package com.zero.ai.agentstudy.day10planningagent.core.planner;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 LLM 的规划器：调用 ChatClient 结构化输出 PlanDto，再转领域对象。
 */
@Component
public class LlmPlanner implements Planner {

    private final ChatClient chatClient;

    public LlmPlanner(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public Plan plan(Goal goal) {
        String prompt = """
                你是一个任务规划专家。请把用户目标拆解为可执行的步骤计划。
                要求：
                1. 每个步骤有唯一 id（如 step-1、step-2）。
                2. action 用简洁中文描述该步要做什么。
                3. tool 从 [browser, llm] 中选择：需要抓取网页用 browser，需要理解/提取/总结/排版用 llm。
                4. priority 取值 LOW / MEDIUM / HIGH。
                5. deps 声明依赖的前置步骤 id（必须先完成前置才能执行本步）。
                6. 步骤要精简，通常 3~6 步即可。

                用户目标：%s
                """.formatted(goal.description());

        PlanDto dto = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(PlanDto.class);

        if (dto == null || dto.steps == null || dto.steps.isEmpty()) {
            throw new IllegalStateException("规划失败：LLM 未产出有效步骤");
        }
        return dto.toDomain();
    }

    @Override
    public Plan replan(PlanningContext ctx) {
        String completed = ctx.completedSummary();
        String lastError = ctx.lastObservation() == null ? "无"
                : (ctx.lastObservation().error() == null ? "无" : ctx.lastObservation().error());

        String prompt = """
                你是任务规划专家。当前任务执行遇到问题，需要重规划。
                请只规划【尚未完成】的剩余步骤，不要重复已完成的工作。

                原始目标：%s

                已完成成果（保留，不要重做）：
                %s

                最近一次失败/问题：%s

                请输出修正后的剩余步骤计划（同样的 JSON 结构，id 从 step-r1 开始编号）。
                """.formatted(ctx.goal().description(),
                completed.isBlank() ? "（暂无）" : completed,
                lastError);

        PlanDto dto = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(PlanDto.class);

        Plan newRemaining = (dto == null || dto.steps == null)
                ? new Plan(List.of()) : dto.toDomain();

        return mergeKeepingDone(ctx.plan(), newRemaining);
    }

    /**
     * 增量合并：保留旧计划中已完成/已结算的步骤，拼接新的剩余步骤。
     */
    private Plan mergeKeepingDone(Plan oldPlan, Plan newRemaining) {
        Plan merged = new Plan(List.of());
        if (oldPlan != null) {
            for (PlanStep s : oldPlan.steps()) {
                if (s.isSettled()) {
                    merged.addStep(s); // 已完成的原样保留
                }
            }
        }
        for (PlanStep s : newRemaining.steps()) {
            merged.addStep(s);
        }
        return merged;
    }
}