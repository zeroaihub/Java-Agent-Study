package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;

/**
 * 命令执行上下文（值对象）。
 * <p>
 * 承载命令执行所需的身份与会话信息，与「解析结果」{@link ParsedCommand} 分离：
 * <ul>
 *     <li>{@link #command}：已解析的命令（命令名 + 参数）。</li>
 *     <li>{@link #tenantId} / {@link #fromUserId} / {@link #conversationId} / {@link #channelType}：
 *         身份与渠道信息，来自入站消息。</li>
 *     <li>{@link #inbound}：原始入站消息，供命令按需读取更多字段（如构造回复）。</li>
 * </ul>
 * 命令实现只依赖本上下文，不直接依赖 wechatrouter / 传输层，保持领域纯净。
 *
 * @param command        已解析命令
 * @param tenantId       租户标识
 * @param fromUserId     发送者用户标识
 * @param conversationId 会话标识
 * @param channelType    渠道类型
 * @param inbound        原始入站消息
 * @author ZeroAi
 */
public record CommandContext(
        ParsedCommand command,
        String tenantId,
        String fromUserId,
        String conversationId,
        ChannelType channelType,
        InboundMessage inbound) {

    /**
     * 由入站消息与解析结果构造上下文。
     *
     * @param inbound 入站消息
     * @param command 解析后的命令
     * @return 命令上下文
     */
    public static CommandContext of(InboundMessage inbound, ParsedCommand command) {
        return new CommandContext(
                command,
                inbound.getTenantId(),
                inbound.getFromUserId(),
                inbound.getConversationId(),
                inbound.getChannelType(),
                inbound);
    }

    /**
     * 命令名快捷访问。
     *
     * @return 命令名
     */
    public String name() {
        return command.name();
    }
}