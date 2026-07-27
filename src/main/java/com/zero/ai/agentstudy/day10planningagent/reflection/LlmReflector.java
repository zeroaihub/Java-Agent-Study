package com.zero.ai.agentstudy.day10planningagent.reflection;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.StepResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 基于 LLM 的反思器（默认注入，@Primary）。
 *
 * <p>让大模型「看着」执行结果与已完成摘要，判断下一步该做什么，产出四种裁决之一。
 * LLM 反思能捕捉规则无法覆盖的语义问题（如「抓到了页面但内容明显不是 Trending 榜单」）。
 *
 * <p>容错设计（企业级要点）：
 * <ul>
 *   <li>LLM 调用/解析出现任何异常，立即降级到 {@link RuleBasedReflector}，保证主循环永不因反思崩溃；</li>
 *   <li>解析结果不在枚举内时，保守返回 CONTINUE（避免误判导致无谓中止）。</li>
 * </ul>
 */
@Component
@Primary
public class LlmReflector implements Reflector {

    private final ChatClient chatClient;
    private final RuleBasedReflector fallback;

    public LlmReflector(ChatClient.Builder builder, RuleBasedReflector fallback) {
        this.chatClient = builder.build();
        this.fallback = fallback;
    }

    @Override
    public Reflection reflect(PlanningContext ctx, PlanStep step, StepResult result) {
        try {
            String prompt = buildPrompt(ctx, step, result);
            String raw = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return parse(raw, ctx, step, result);
        } catch (Exception e) {
            // 任何异常都降级到规则反思，绝不让反思环节拖垮主循环
            Reflection rb = fallback.reflect(ctx, step, result);
            return new Reflection(rb.verdict(),
                    "LLM 反思异常降级到规则反思(" + e.getClass().getSimpleName() + "): " + rb.reason());
        }
    }

    private String buildPrompt(PlanningContext ctx, PlanStep step, StepResult result) {
        return """
                你是一个严谨的任务反思专家。请评估刚执行的步骤结果，并决定下一步动作。
                只能从以下四个裁决中选择一个，且必须在回答的第一行仅输出该裁决单词（大写）：
                - CONTINUE   ：本步结果符合预期，继续执行后续步骤
                - RETRY_STEP ：本步失败但值得原样重试
                - REPLAN     ：当前计划路径行不通，需要重新规划
                - ABORT      ：无法恢复，应中止任务

                第二行起可补充简短理由。

                【总体目标】%s
                【当前步骤】%s -> %s (已尝试 %d 次)
                【执行是否成功】%s
                【本步输出/错误】%s
                【已完成步骤摘要】
                %s
                """.formatted(
                ctx.goal().description(),
                step.id(), step.action(), step.attemptCount(),
                result.success(),
                result.success() ? brief(result.output()) : brief(result.error()),
                ctx.completedSummary().isBlank() ? "(无)" : ctx.completedSummary()
        );
    }

    private Reflection parse(String raw, PlanningContext ctx, PlanStep step, StepResult result) {
        if (raw == null || raw.isBlank()) {
            return fallback.reflect(ctx, step, result);
        }
        String head = raw.strip().split("\\R", 2)[0].trim().toUpperCase();
        String reason = raw.strip();
        return switch (head) {
            case "CONTINUE" -> Reflection.cont(reason);
            case "RETRY_STEP" -> Reflection.retry(reason);
            case "REPLAN" -> Reflection.replan(reason);
            case "ABORT" -> Reflection.abort(reason);
            // 解析不到明确裁决时保守放行，避免误伤
            default -> Reflection.cont("LLM 未给出明确裁决，保守继续。原文: " + brief(reason));
        };
    }

    private String brief(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}