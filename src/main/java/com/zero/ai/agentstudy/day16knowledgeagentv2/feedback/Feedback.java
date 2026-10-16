package com.zero.ai.agentstudy.day16knowledgeagentv2.feedback;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.io.Serializable;
import java.time.Instant;

/**
 * 一条结构化反馈记录（Chapter 12）。
 *
 * <p>traceId 是归因的关键锚点——串起当次回答的全链路痕迹。收集与归因分离：
 * 本记录只负责承载「谁在何时对哪次回答给了什么反馈」。</p>
 *
 * @param traceId   被反馈的那次回答的链路 ID
 * @param query     原始问题
 * @param type      反馈类型
 * @param comment   用户补充（纠错内容 / 吐槽），可空
 * @param createdAt 反馈时间
 * @author ZeroAi
 */
public record Feedback(
        String traceId,
        String query,
        FeedbackType type,
        String comment,
        Instant createdAt
) implements Serializable {

    public Feedback {
        AgentException.requireText(traceId, "traceId");
        AgentException.requireText(query, "query");
        AgentException.requireNonNull(type, "type");
        comment = (comment == null) ? "" : comment;
        createdAt = (createdAt == null) ? Instant.now() : createdAt;
    }

    /** 便捷工厂：当前时刻。 */
    public static Feedback of(String traceId, String query, FeedbackType type, String comment) {
        return new Feedback(traceId, query, type, comment, Instant.now());
    }
}