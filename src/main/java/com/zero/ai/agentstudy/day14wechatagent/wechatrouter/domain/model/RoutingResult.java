package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;

import java.util.Optional;

/**
 * 路由结果（值对象）。
 * <p>
 * 描述一次完整路由的结论：命中的意图、承接的处理器名、最终回复（可空）。
 * 上层（API 层）据此发送回复并记录观测指标（按意图/处理器维度）。
 *
 * @param intentType  最终判定的意图类型
 * @param handlerName 承接消息的处理器类名（未命中时为 {@code "none"}）
 * @param replyOrNull 最终回复（可为 null，表示无需回复）
 * @param handled     是否被某个处理器成功处理
 * @author ZeroAi
 */
public record RoutingResult(IntentType intentType,
                            String handlerName,
                            OutboundMessage replyOrNull,
                            boolean handled) {

    private static final String NO_HANDLER = "none";

    /**
     * 构造一个"已处理"的路由结果。
     *
     * @param intentType  意图类型
     * @param handlerName 处理器名
     * @param reply       回复（可空）
     * @return 结果
     */
    public static RoutingResult handled(IntentType intentType, String handlerName, OutboundMessage reply) {
        return new RoutingResult(intentType, handlerName, reply, true);
    }

    /**
     * 构造一个"未命中任何处理器"的路由结果。
     * <p>
     * 生产中理应不出现（有兜底处理器），此方法用于防御性返回与告警埋点。
     *
     * @param intentType 意图类型（通常为 {@link IntentType#UNKNOWN}）
     * @return 结果
     */
    public static RoutingResult unhandled(IntentType intentType) {
        return new RoutingResult(intentType, NO_HANDLER, null, false);
    }

    /**
     * 安全获取回复。
     *
     * @return 回复的 Optional 包装
     */
    public Optional<OutboundMessage> reply() {
        return Optional.ofNullable(replyOrNull);
    }

    /** 是否有可发送的回复。 */
    public boolean hasReply() {
        return replyOrNull != null;
    }
}