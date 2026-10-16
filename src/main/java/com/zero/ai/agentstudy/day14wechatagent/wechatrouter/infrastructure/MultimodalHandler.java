package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.HandleResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.IntentType;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.port.MessageHandler;
import org.springframework.stereotype.Component;

/**
 * 多模态处理器（占位实现，优先级次于命令）。
 * <p>
 * 认领图片/文件类消息（{@link InboundMessage#isMultimodal()}），后续章节接入
 * 图片理解（Vision）与文件解析（RAG 入库）。当前先给出占位回复。
 *
 * @author ZeroAi
 */
@Component
public class MultimodalHandler implements MessageHandler {

    /** 优先级：次于命令，先于闲聊。 */
    private static final int ORDER = 20;

    @Override
    public IntentType intentType() {
        return IntentType.MULTIMODAL;
    }

    @Override
    public boolean supports(InboundMessage message) {
        return message.isMultimodal();
    }

    @Override
    public HandleResult handle(InboundMessage message) {
        // TODO 后续章节接入图片理解（Vision）与文件解析（RAG）。
        String reply = "已收到" + message.getMessageType().name() + "消息"
                + (message.getMediaUrl() != null ? "（含媒体资源）" : "")
                + "，多模态解析能力将在后续章节接入。";
        return HandleResult.replied(OutboundMessage.textReply(message, reply));
    }

    @Override
    public int order() {
        return ORDER;
    }
}