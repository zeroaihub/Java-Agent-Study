package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.MessageType;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.HandleResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.IntentType;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.port.MessageHandler;
import org.springframework.stereotype.Component;

/**
 * 闲聊处理器（占位实现，优先级低于命令与多模态）。
 * <p>
 * 认领普通文本消息（非命令），后续章节接入 LLM 对话（Spring AI ChatClient）
 * 与多轮 Memory。当前先给出占位回复。
 *
 * @author ZeroAi
 */
@Component
public class ChatHandler implements MessageHandler {

    /** 优先级：低于命令(10)与多模态(20)，高于兜底回显。 */
    private static final int ORDER = 30;

    @Override
    public IntentType intentType() {
        return IntentType.CHAT;
    }

    @Override
    public boolean supports(InboundMessage message) {
        // 认领所有普通文本（命令已被前序 CommandHandler 拦截）。
        return message.getMessageType() == MessageType.TEXT
                && message.getContent() != null
                && !message.getContent().isBlank();
    }

    @Override
    public HandleResult handle(InboundMessage message) {
        // TODO 后续章节接入 Spring AI ChatClient + Memory 实现真正的多轮对话。
        String reply = "收到你的消息：" + message.getContent()
                + "\n（智能对话能力将在后续章节接入 LLM）";
        return HandleResult.replied(OutboundMessage.textReply(message, reply));
    }

    @Override
    public int order() {
        return ORDER;
    }
}