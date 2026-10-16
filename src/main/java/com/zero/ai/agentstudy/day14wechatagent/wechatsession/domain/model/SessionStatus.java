package com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.model;

/**
 * 会话状态（值对象-枚举）。
 * <p>
 * 会话是一个轻量状态机：新建后处于 ACTIVE，长时间无交互变为 IDLE，
 * 超过过期阈值则 EXPIRED。命令系统可将会话切入 WAITING_INPUT（等待用户补充信息）
 * 或 WAITING_APPROVAL（等待人工审批，对应 Human-in-the-loop）。
 *
 * @author ZeroAi
 */
public enum SessionStatus {

    /** 活跃：正常多轮对话中 */
    ACTIVE,

    /** 空闲：一段时间无交互，但尚未过期 */
    IDLE,

    /** 等待用户补充输入（如缺少必填参数） */
    WAITING_INPUT,

    /**等待人工审批（Human-in-the-loop） */
    WAITING_APPROVAL,

    /** 已过期：上下文失效，下次交互将重建会话 */
    EXPIRED;

    /** 是否为可继续对话的“存活”状态。 */
    public boolean isAlive() {
        return this != EXPIRED;
    }
}