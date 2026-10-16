package com.zero.ai.agentstudy.day16knowledgeagentv2.grounding;

import java.util.List;

/**
 * 接地校验后的答案（Chapter 09~11）。
 *
 * <p>把「答案文本 + 引用 + 是否接地 + 置信度」打包成一个不可变结果，交给上层决定是否
 * 展示给用户。核心红线：<b>宁可拒答，不可幻觉</b>——{@code grounded=false} 时，上层应
 * 返回拒答话术而非硬输出。</p>
 *
 * @param answer     答案文本
 * @param citations  引用来源
 * @param grounded   是否有检索证据支撑
 * @param confidence 置信度 0~1
 * @param reason     判定理由（用于日志/审计）
 * @author ZeroAi
 */
public record GroundedAnswer(
        String answer,
        List<Citation> citations,
        boolean grounded,
        double confidence,
        String reason
) {

    public GroundedAnswer {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** 未接地：构造一个安全的拒答结果。 */
    public static GroundedAnswer refuse(String reason) {
        return new GroundedAnswer(
                "抱歉，我在现有知识库中没有找到足够依据来回答这个问题。",
                List.of(), false, 0.0, reason);
    }

    /** 接地成功。 */
    public static GroundedAnswer of(String answer, List<Citation> citations, double confidence) {
        return new GroundedAnswer(answer, citations, true, confidence, "grounded");
    }
}