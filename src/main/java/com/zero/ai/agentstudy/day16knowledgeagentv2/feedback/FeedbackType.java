package com.zero.ai.agentstudy.day16knowledgeagentv2.feedback;

/**
 * 反馈信号类型：统一显式与隐式反馈（Chapter 12）。
 *
 * <p>显式反馈（用户主动操作，信噪比高但量少）与隐式反馈（系统观测行为，
 * 嘈杂但丰富）统一到同一模型，用类型字段区分。只看显式会漏掉「大多数
 * 不满意的人只是默默离开」的沉默大多数。</p>
 *
 * @author ZeroAi
 */
public enum FeedbackType {

    /** 显式点赞（正向）。 */
    THUMBS_UP(true),
    /** 显式点踩（负向）。 */
    THUMBS_DOWN(true),
    /** 显式纠错，附正确内容（负向，但价值最高，可写回知识库）。 */
    CORRECTION(true),
    /** 隐式：复制了答案（正向信号）。 */
    COPIED(false),
    /** 隐式：未解决即放弃会话（负向信号）。 */
    ABANDONED(false);

    private final boolean explicit;

    FeedbackType(boolean explicit) {
        this.explicit = explicit;
    }

    /** 是否为用户主动操作的显式反馈。 */
    public boolean isExplicit() {
        return explicit;
    }

    /** 是否为负向信号（用于快速筛选待优化项）。 */
    public boolean isNegative() {
        return this == THUMBS_DOWN || this == ABANDONED;
    }
}