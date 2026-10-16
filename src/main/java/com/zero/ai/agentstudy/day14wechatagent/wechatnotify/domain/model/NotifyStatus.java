package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model;

/**
 * 通知投递状态。
 *
 * <p>一条通知从创建到最终送达，会经历以下状态流转：</p>
 * <pre>
 *   PENDING --(投递)--> SENT        (成功送达)
 *      |                  
 *      +----(投递失败)--> FAILED     (可重投则回到 PENDING)
 *      |
 *      +----(超限/主动)-> DEAD       (进入死信，不再重试)
 * </pre>
 *
 * @author ZeroHub AI Agent Platform
 */
public enum NotifyStatus {

    /** 待投递（初始态，或失败后等待重投）。 */
    PENDING,

    /** 已成功送达。 */
    SENT,

    /** 本次投递失败（仍可能重投）。 */
    FAILED,

    /** 死信：重试耗尽或不可重试，进入死信队列人工处理。 */
    DEAD;

    /**
     * 是否为终态（不再变化）。
     *
     * @return true 表示 SENT 或 DEAD
     */
    public boolean isTerminal() {
        return this == SENT || this == DEAD;
    }

    /**
     * 是否可再次投递。
     *
     * @return true 表示 PENDING 或 FAILED
     */
    public boolean isRetryable() {
        return this == PENDING || this == FAILED;
    }
}