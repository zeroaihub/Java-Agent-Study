package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.HandleResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.IntentType;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.RoutingResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.port.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 消息路由器（应用层编排）。
 * <p>
 * 责任链 + 策略模式的编排者：
 * <ol>
 *   <li>启动时收集所有 {@link MessageHandler}，按 {@link MessageHandler#order()} 升序排队；</li>
 *   <li>一条消息进来，依次询问 {@code supports}，命中的第一个执行 {@code handle}；</li>
 *   <li>处理器返回 {@link HandleResult}：终止则收工，否则继续问下一个；</li>
 *   <li>全链走完仍无人认领，返回 {@link RoutingResult#unhandled}（生产有兜底，理应不出现）。</li>
 * </ol>
 * 新增意图只需新增一个 {@code MessageHandler} 实现（OCP），路由器无需改动。
 *
 * @author ZeroAi
 */
@Service
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    /** 按优先级升序排好的处理器链（构造期固化，运行期只读）。 */
    private final List<MessageHandler> handlers;

    /**
     * 由 Spring 注入所有 {@link MessageHandler} Bean。
     *
     * @param handlers 处理器集合（可能为空，此时路由恒返回 unhandled）
     */
    public MessageRouter(List<MessageHandler> handlers) {
        this.handlers = handlers.stream()
                .sorted(Comparator.comparingInt(MessageHandler::order))
                .toList();
        log.info("MessageRouter 初始化完成，责任链共 {} 个处理器：{}",
                this.handlers.size(),
                this.handlers.stream().map(h -> h.getClass().getSimpleName()).toList());
    }

    /**
     * 路由并处理一条入站消息。
     *
     * @param message 入站消息（非空）
     * @return 路由结果
     */
    public RoutingResult route(InboundMessage message) {
        // 前置守卫：不该处理的消息（如群聊未 @）直接短路，不占用责任链。
        if (!message.shouldHandle()) {
            log.debug("消息无需处理（群聊未@或已过滤），tenantId={}, msgId={}",
                    message.getTenantId(), message.getMsgId());
            return RoutingResult.unhandled(IntentType.UNKNOWN);
        }

        for (MessageHandler handler : handlers) {
            if (!handler.supports(message)) {
                continue;
            }
            String handlerName = handler.getClass().getSimpleName();
            log.debug("命中处理器 {}（意图={}），tenantId={}, msgId={}",
                    handlerName, handler.intentType(), message.getTenantId(), message.getMsgId());

            HandleResult result = handler.handle(message);
            if (result.handled()) {
                return RoutingResult.handled(handler.intentType(), handlerName, result.reply());
            }
            // 处理器主动放行（pass），继续问下一个。
            if (result.terminal()) {
                break;
            }
        }

        log.warn("无处理器认领消息，tenantId={}, msgId={}，请检查是否缺少兜底处理器",
                message.getTenantId(), message.getMsgId());
        return RoutingResult.unhandled(IntentType.UNKNOWN);
    }

    /** 当前责任链长度（监控/自检用）。 */
    public int handlerCount() {
        return handlers.size();
    }
}