package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 入站消息（聚合根 / 不可变值对象）。
 * <p>
 * 表示从任意渠道（企业微信 / Webhook / Mock）接收到的一条原始消息，
 * 经过渠道适配器归一化后进入消息管线。设计要点：
 * <ul>
 *     <li>不可变：所有字段 final，仅通过 Builder 构建，避免管线中被篡改。</li>
 *     <li>多租户：{@code tenantId} 贯穿全链路，是行级隔离与配额计费的基础。</li>
 *     <li>幂等键：{@code msgId} 由渠道保证唯一，用于 Redis SETNX 去重。</li>
 *     <li>渠道无关：领域层不感知具体渠道协议，只认识归一化后的字段。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Getter
@ToString
@Builder(toBuilder = true)
public class InboundMessage {

    /** 租户标识（多租户隔离键，必填） */
    private final String tenantId;

    /** 渠道消息唯一 ID（幂等键，必填） */
    private final String msgId;

    /** 来源渠道类型 */
    private final ChannelType channelType;

    /** 聊天类型：单聊 / 群聊 */
    private final ChatType chatType;

    /** 会话标识（单聊为对方 userId，群聊为群 ID） */
    private final String conversationId;

    /** 发送者用户 ID */
    private final String fromUserId;

    /** 发送者显示名（可空） */
    private final String fromUserName;

    /** 群聊场景下机器人是否被 @（单聊恒为 true 语义上等价） */
    @Builder.Default
    private final boolean mentioned = true;

    /** 消息内容类型 */
    private final MessageType messageType;

    /** 归一化后的文本内容（图片/文件消息此处为描述或空） */
    private final String content;

    /** 媒体资源地址（图片/文件的临时下载地址，可空） */
    private final String mediaUrl;

    /** 渠道原始扩展字段（只读快照，便于适配器透传附加信息） */
    @Builder.Default
    private final Map<String, Object> attributes = Collections.emptyMap();

    /** 消息发生时间（渠道侧时间戳，缺省取接收时间） */
    private final Instant occurredAt;

    /** 链路追踪 ID（贯穿管线，用于日志与 Trace 关联） */
    private final String traceId;

    /**
     * 是否需要被 Agent 处理。
     * <p>
     * 单聊默认处理；群聊仅当机器人被 @ 时处理，避免刷屏与无效算力消耗。
     */
    public boolean shouldHandle() {
        if (chatType == ChatType.SINGLE) {
            return true;
        }
        return mentioned;
    }

    /** 是否为多模态消息（图片/文件），需要进入解析或多模态链路。 */
    public boolean isMultimodal() {
        return messageType == MessageType.IMAGE || messageType == MessageType.FILE;
    }

    /** 安全读取扩展属性。 */
    public Object attribute(String key) {
        return attributes == null ? null : attributes.get(key);
    }

    /**
     * 幂等键：租户维度下以 msgId 唯一标识一条消息。
     * <p>
     * 不同租户可能出现相同 msgId，因此以 {tenantId}:{msgId} 组合作为去重键。
     */
    public String idempotencyKey() {
        return tenantId + ":" + msgId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InboundMessage that)) {
            return false;
        }
        return Objects.equals(this.idempotencyKey(), that.idempotencyKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(idempotencyKey());
    }
}