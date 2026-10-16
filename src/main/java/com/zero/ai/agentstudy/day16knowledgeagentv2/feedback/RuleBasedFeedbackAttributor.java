package com.zero.ai.agentstudy.day16knowledgeagentv2.feedback;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

/**
 * 基于规则的反馈归因器（Chapter 12）。
 *
 * <p>读链路快照，把负向反馈定位到具体环节。归因优先级（从最可能的根因开始）：
 * 检索命中弱 → 权限拦截 → 引用少（排序/生成）→ 验证未过。</p>
 *
 * <p>收集、归因、处置分离：本类只做归因，处置（即时补救 / 落库）交给上层编排。
 * 生产中可替换为基于 LLM 的归因器，接口不变。</p>
 *
 * @author ZeroAi
 */
public final class RuleBasedFeedbackAttributor {

    private final double weakScoreThreshold;

    public RuleBasedFeedbackAttributor(double weakScoreThreshold) {
        AgentException.requireState(weakScoreThreshold >= 0 && weakScoreThreshold <= 1,
                AgentErrorCode.INVALID_ARGUMENT, "weakScoreThreshold 必须在 [0,1]");
        this.weakScoreThreshold = weakScoreThreshold;
    }

    /**
     * 对一条反馈做归因。
     *
     * @param feedback 反馈记录
     * @param trace    对应的链路快照
     * @return 归因结论（含原因与优化建议）
     */
    public AttributionResult attribute(Feedback feedback, AnswerTrace trace) {
        AgentException.requireNonNull(feedback, "feedback");
        AgentException.requireNonNull(trace, "trace");

        // 正向 / 非负向反馈无需归因（纠错虽非「负向枚举」，但需要归因到生成环节）
        if (!feedback.type().isNegative() && feedback.type() != FeedbackType.CORRECTION) {
            return AttributionResult.NONE;
        }

        // 1. 检索本身就弱：最高分低于阈值 ⇒ 检索未命中
        if (trace.topScore() < weakScoreThreshold) {
            return AttributionResult.RETRIEVAL_MISS;
        }

        // 2. 发生过权限过滤且没引用到东西 ⇒ 可能被权限挡了
        if (trace.aclFiltered() && trace.citationCount() == 0) {
            return AttributionResult.ACCESS_ERROR;
        }

        // 3. 检索分不低但引用很少 ⇒ 对的片段没被排上来（排序问题）
        if (trace.citationCount() == 0) {
            return AttributionResult.RANKING_ERROR;
        }

        // 4. 有引用、检索也不弱，但验证没过或用户纠错 ⇒ 生成环节写错
        if (!trace.verified() || feedback.type() == FeedbackType.CORRECTION) {
            return AttributionResult.GENERATION_ERROR;
        }

        // 引用充足、验证也过，却仍被点踩：暂无法自动归因
        return AttributionResult.NONE;
    }
}