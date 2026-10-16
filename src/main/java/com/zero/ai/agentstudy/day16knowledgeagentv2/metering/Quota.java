package com.zero.ai.agentstudy.day16knowledgeagentv2.metering;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

/**
 * 租户配额定义（Chapter 16）。
 *
 * <p>SaaS 按套餐售卖能力，配额是把商业约束落到技术上的抓手：每日问答上限、并发上限、
 * 每分钟令牌桶速率。它决定了「谁能用多少」，是计量与限流的基准。</p>
 *
 * @param dailyQueryLimit 每日问答次数上限
 * @param ratePerMinute   每分钟允许的请求数（令牌桶容量/补充速率）
 * @author ZeroAi
 */
public record Quota(long dailyQueryLimit, long ratePerMinute) {

    public Quota {
        AgentException.requireState(dailyQueryLimit > 0,
                AgentErrorCode.INVALID_ARGUMENT, "dailyQueryLimit 必须为正");
        AgentException.requireState(ratePerMinute > 0,
                AgentErrorCode.INVALID_ARGUMENT, "ratePerMinute 必须为正");
    }

    /**免费套餐。 */
    public static Quota free() {
        return new Quota(100, 10);
    }

    /** 企业套餐。 */
    public static Quota enterprise() {
        return new Quota(100_000, 600);
    }
}