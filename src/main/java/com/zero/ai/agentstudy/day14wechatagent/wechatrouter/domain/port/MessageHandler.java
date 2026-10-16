package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.HandleResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.IntentType;

/**
 * 消息处理器（出站端口 / 责任链节点）。
 * <p>
 * 每个处理器负责一种意图的消息。路由器会按 {@link #order()} 从小到大依次询问
 * {@link #supports(InboundMessage)}，命中的第一个处理器执行 {@link #handle}。
 * <p>
 * 这是"策略模式 + 责任链模式"的融合：
 * <ul>
 *   <li>策略：每个处理器是一种独立的处理策略，可插拔（OCP，新增意图不改路由器）。</li>
 *   <li>责任链：处理器按优先级排队，支持"我不认领就传给下一个"。</li>
 * </ul>
 * 实现类通过 Spring 注入为 {@code List<MessageHandler>}，天然支持扩展。
 *
 * @author ZeroAi
 */
public interface MessageHandler {

    /** 默认优先级（兜底处理器应显式给更大的值）。 */
    int DEFAULT_ORDER = 100;

    /**
     * 本处理器负责的意图类型（用于路由结果观测与日志）。
     *
     * @return 意图类型
     */
    IntentType intentType();

    /**
     * 是否认领这条消息。
     * <p>
     * 路由器按 {@link #order()} 升序遍历，命中的第一个处理器胜出。
     *
     * @param message 入站消息
     * @return true 表示由本处理器处理
     */
    boolean supports(InboundMessage message);

    /**
     * 处理消息并返回结果。
     * <p>
     * 注意：此处仅传入 {@link InboundMessage}，会话状态在应用层
     * （{@code MessageRouter}）注入后由处理器按需消费，保持端口纯净。
     *
     * @param message 入站消息
     * @return 处理结果（含回复与是否终止链路）
     */
    HandleResult handle(InboundMessage message);

    /**
     * 责任链优先级，数值越小越先被询问。
     * <p>
     * 约定：命令 < 多模态 < 闲聊 < 兜底回显。
     * 默认返回 {@link #DEFAULT_ORDER}。
     *
     * @return 优先级
     */
    default int order() {
        return DEFAULT_ORDER;
    }
}