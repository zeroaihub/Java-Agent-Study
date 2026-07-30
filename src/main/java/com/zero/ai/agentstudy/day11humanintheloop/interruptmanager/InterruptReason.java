package com.zero.ai.agentstudy.day11humanintheloop.interruptmanager;

/**
 * 中断原因（Interrupt Reason）。
 *
 * <p>Agent 执行被打断可能有多种触发源，记录原因对审计、恢复策略选择、监控都很关键。</p>
 */
public enum InterruptReason {

    /** 触发了审批网关：动作风险达到阈值，需人工审批。 */
    APPROVAL_REQUIRED,

    /** 人工主动干预：运维/操作员手动点了「暂停」。 */
    HUMAN_INTERVENTION,

    /** 系统保护：如资源超限、熔断、限流触发的被动中断。 */
    SYSTEM_GUARD,

    /** 等待外部输入：需要人类补充信息才能继续（Human Feedback）。 */
    WAITING_INPUT;

    /** 是否由「人」主动触发（区别于系统/规则触发）。 */
    public boolean isHumanTriggered() {
        return this == HUMAN_INTERVENTION || this == WAITING_INPUT;
    }
}