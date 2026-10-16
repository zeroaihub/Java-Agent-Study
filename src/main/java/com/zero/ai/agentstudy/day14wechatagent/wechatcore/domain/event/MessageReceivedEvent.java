package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.event;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;

import java.time.Instant;

/**
 * 领域事件：消息已接收。
 * <p>
 * 当渠道适配器成功接收并归一化一条入站消息、且通过幂等校验后，
 * 由应用层发布该事件。下游（路由、审计、指标）以事件驱动方式解耦消费，
 * 契合「一切皆事件」的设计哲学与 Spring Modulith 的模块间通信约束。
 *
 * @param message    归一化后的入站消息
 * @param occurredAt 事件发生时间
 * @author ZeroAi
 */
public record MessageReceivedEvent(InboundMessage message, Instant occurredAt) {

    public MessageReceivedEvent(InboundMessage message) {
        this(message, Instant.now());
    }

    /** 便捷读取租户 ID，供事件消费方做多租户上下文切换。 */
    public String tenantId() {
        return message.getTenantId();
    }

    /** 便捷读取链路追踪 ID。 */
    public String traceId() {
        return message.getTraceId();
    }
}