package com.zero.ai.agentstudy.day14wechatagent.wechatcore.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.port.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mock 渠道适配器（本地兜底）。
 * <p>
 * 不依赖任何企业微信凭证，直接把出站消息打印到日志，用于本地开发、
 * 单元测试与全链路联调。配合 {@link ChannelType#of(String)} 的未知回退 MOCK 机制，
 * 保证课程在零外部凭证下即可端到端跑通。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class MockMessageChannel implements MessageChannel {

    @Override
    public ChannelType channelType() {
        return ChannelType.MOCK;
    }

    @Override
    public void send(OutboundMessage message) {
        log.info("[MOCK-CHANNEL] tenant={} conv={} to={} type={} traceId={}\n----- 消息正文 -----\n{}\n--------------------",
                message.getTenantId(),
                message.getConversationId(),
                message.getToUserId(),
                message.getMessageType(),
                message.getTraceId(),
                message.getContent());
    }
}