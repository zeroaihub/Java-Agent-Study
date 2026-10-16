package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;

/**
 * 处理结果（值对象）。
 * <p>
 * 描述一个 {@code MessageHandler} 对消息的处理结论：是否已处理、
 * 产生的回复（可空）、以及是否终止责任链。
 *
 * @param handled  是否已被本处理器处理
 * @param reply    要发送的回复（可为 null，表示无需回复）
 * @param terminal 是否终止责任链（true 则后续处理器不再执行）
 * @author ZeroAi
 */
public record HandleResult(boolean handled, OutboundMessage reply, boolean terminal) {

    /**
     * 已处理并给出回复，且终止链路（最常见）。
     *
     * @param reply 回复消息
     * @return 结果
     */
    public static HandleResult replied(OutboundMessage reply) {
        return new HandleResult(true, reply, true);
    }

    /**
     * 已处理但无需回复（如静默记录），终止链路。
     *
     * @return 结果
     */
    public static HandleResult handledSilently() {
        return new HandleResult(true, null, true);
    }

    /**
     * 本处理器不认领此消息，交由链路中的下一个处理器。
     *
     * @return 结果
     */
    public static HandleResult pass() {
        return new HandleResult(false, null, false);
    }

    /** 是否有可发送的回复。 */
    public boolean hasReply() {
        return reply != null;
    }
}