package com.zero.ai.agentstudy.day14wechatagent.wechatcore.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.exception.WeChatAgentException;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.port.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道注册中心（Strategy + Registry 模式）。
 * <p>
 * 在应用启动时收集容器内所有 {@link MessageChannel} 实现，按 {@link ChannelType}
 * 建立索引。出站投递时根据 {@link OutboundMessage#getChannelType()} 选择对应适配器，
 * 实现「渠道可插拔」——新增渠道只需注册一个 Bean，无需改动本类。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class MessageChannelRegistry {

    private final Map<ChannelType, MessageChannel> channels = new EnumMap<>(ChannelType.class);

    /**
     * 构造时注入所有渠道适配器 Bean，并建立类型索引。
     *
     * @param messageChannels 容器内全部 {@link MessageChannel} 实现
     */
    public MessageChannelRegistry(List<MessageChannel> messageChannels) {
        for (MessageChannel channel : messageChannels) {
            MessageChannel prev = channels.put(channel.channelType(), channel);
            if (prev != null) {
                log.warn("渠道类型 {} 存在多个适配器实现，后者 {} 覆盖前者 {}",
                        channel.channelType(), channel.getClass().getSimpleName(),
                        prev.getClass().getSimpleName());
            }
        }
        log.info("渠道注册中心初始化完成，已注册渠道: {}", channels.keySet());
    }

    /**
     * 按渠道类型选择适配器。
     *
     * @param channelType 渠道类型
     * @return 对应的渠道适配器
     * @throws WeChatAgentException 当渠道未注册时抛出 {@code CHANNEL_UNSUPPORTED}
     */
    public MessageChannel resolve(ChannelType channelType) {
        MessageChannel channel = channels.get(channelType);
        if (channel == null) {
            throw new WeChatAgentException(
                    WeChatAgentException.ErrorCode.CHANNEL_UNSUPPORTED,
                    "未注册的渠道类型: " + channelType);
        }
        return channel;
    }

    /**
     * 投递出站消息：解析渠道 → 校验租户支持 → 发送。
     *
     * @param message 出站消息
     */
    public void dispatch(OutboundMessage message) {
        MessageChannel channel = resolve(message.getChannelType());
        if (!channel.supports(message.getTenantId())) {
            throw new WeChatAgentException(
                    WeChatAgentException.ErrorCode.TENANT_FORBIDDEN,
                    "渠道 " + message.getChannelType() + " 未对租户 "
                            + message.getTenantId() + " 开放");
        }
        channel.send(message);
    }
}