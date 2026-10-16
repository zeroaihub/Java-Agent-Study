package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;

/**
 * 出站消息（值对象）。
 * <p>
 * 表示 Agent 编排完成后、准备通过渠道端口回复给用户的一条消息。
 * 领域层生产 OutboundMessage，由出站适配器 {@code MessageChannel} 转换为
 * 具体渠道协议并投递。
 * <p>
 * 设计要点：
 * <ul>
 *     <li>不可变，仅通过 Builder 构建。</li>
 *     <li>携带 traceId 与入站保持一致，实现请求-回复的链路串联。</li>
 *     <li>默认回复类型为 MARKDOWN，契合企业机器人富文本场景。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class OutboundMessage {

    /** 租户标识 */
    private final String tenantId;

    /** 目标渠道类型 */
    private final ChannelType channelType;

    /** 会话标识（回复回同一会话） */
    private final String conversationId;

    /** 目标用户 ID（单聊必填；群聊可空表示群内广播） */
    private final String toUserId;

    /** 回复内容类型 */
    @Builder.Default
    private final MessageType messageType = MessageType.MARKDOWN;

    /** 回复正文（TEXT/MARKDOWN 有效） */
    private final String content;

    /** 媒体资源地址（IMAGE/FILE/CARD 有效，可空） */
    private final String mediaUrl;

    /** 链路追踪 ID（与入站消息一致） */
    private final String traceId;

    /** 生成时间 */
    @Builder.Default
    private final Instant createdAt = Instant.now();

    /**
     * 快捷构建一条 Markdown 文本回复。
     *
     * @param inbound 入站消息（回复目标从中派生）
     * @param markdown Markdown 正文
     */
    public static OutboundMessage markdownReply(InboundMessage inbound, String markdown) {
        return OutboundMessage.builder()
                .tenantId(inbound.getTenantId())
                .channelType(inbound.getChannelType())
                .conversationId(inbound.getConversationId())
                .toUserId(inbound.getFromUserId())
                .messageType(MessageType.MARKDOWN)
                .content(markdown)
                .traceId(inbound.getTraceId())
                .build();
    }

    /**
     * 快捷构建一条纯文本回复。
     */
    public static OutboundMessage textReply(InboundMessage inbound, String text) {
        return OutboundMessage.builder()
                .tenantId(inbound.getTenantId())
                .channelType(inbound.getChannelType())
                .conversationId(inbound.getConversationId())
                .toUserId(inbound.getFromUserId())
                .messageType(MessageType.TEXT)
                .content(text)
                .traceId(inbound.getTraceId())
                .build();
    }
}