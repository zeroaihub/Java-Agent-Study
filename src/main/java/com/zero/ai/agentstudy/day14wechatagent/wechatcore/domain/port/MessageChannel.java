package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;

/**
 * 出站端口：消息渠道（六边形架构 Driven Port）。
 * <p>
 * 领域层通过该端口把 {@link OutboundMessage} 投递到外部 IM 系统，
 * 而不感知具体渠道协议。每种渠道（企业微信 / Webhook / Mock）提供一个
 * 出站适配器实现，由 {@code MessageChannelRegistry} 按 {@link ChannelType} 选择。
 * <p>
 * 这是「渠道可插拔」设计哲学的技术落点：新增渠道只需实现本接口，
 * 领域与应用层零改动。
 *
 * @author ZeroAi
 */
public interface MessageChannel {

    /**
     * 该适配器支持的渠道类型。
     *
     * @return 渠道类型
     */
    ChannelType channelType();

    /**
     * 判断该适配器是否可服务于指定租户。
     * <p>
     * 用于多租户场景下按租户开关渠道、或按租户加载不同凭证。
     * 默认全部支持，特定实现可覆盖以做租户级配额 / 灰度控制。
     *
     * @param tenantId 租户标识
     * @return 是否支持
     */
    default boolean supports(String tenantId) {
        return true;
    }

    /**
     * 投递一条出站消息。
     * <p>
     * 实现需保证：<br>
     * 1) 失败时抛出携带 {@code CHANNEL_DELIVERY_FAILED} 的领域异常；<br>
     * 2) 记录带 traceId 的结构化日志；<br>
     * 3) 对可重试错误交由上层重试策略处理，不在此吞异常。
     *
     * @param message 出站消息
     */
    void send(OutboundMessage message);
}