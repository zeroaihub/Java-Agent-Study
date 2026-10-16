package com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.event;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;

import java.time.Instant;

/**
 * 领域事件：会话过期。
 * <p>
 * 当过期扫描判定某会话进入 {@code EXPIRED} 状态时发布。下游可据此
 * 释放资源、清理长期无用的槽位、推送「会话已结束」提示，或做用户
 * 活跃度统计。事件只携带身份信息与时间戳，保持轻量与可序列化。
 *
 * @param tenantId       租户标识
 * @param channelType    渠道类型
 * @param conversationId 会话标识
 * @param sessionKey     会话身份键（{tenantId}:{channelCode}:{conversationId}）
 * @param turnCount      过期时累计的交互轮次
 * @param expiredAt      过期判定时间
 * @author ZeroAi
 */
public record SessionExpiredEvent(
        String tenantId,
        ChannelType channelType,
        String conversationId,
        String sessionKey,
        long turnCount,
        Instant expiredAt
) {
    /** 便捷工厂：过期时间取当前时刻。 */
    public static SessionExpiredEvent of(String tenantId, ChannelType channelType,
                                         String conversationId, String sessionKey,
                                         long turnCount) {
        return new SessionExpiredEvent(tenantId, channelType, conversationId,
                sessionKey, turnCount, Instant.now());
    }
}