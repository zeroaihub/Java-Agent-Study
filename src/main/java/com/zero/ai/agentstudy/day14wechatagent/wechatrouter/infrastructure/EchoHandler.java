package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.HandleResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.IntentType;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.port.MessageHandler;
import org.springframework.stereotype.Component;

/**
 * 兜底回显处理器（责任链最后一环）。
 * <p>
 * {@link #supports} 恒为 true 且优先级最低，确保任何走到链尾的消息都有回复，
 * 不会出现"用户发消息机器人不吭声"的黑洞。这是责任链"必须有默认分支"的保证，
 * 也让整套系统在零 LLM/零外部依赖时即可运行、可演示、可测试。
 *
 * @author ZeroAi
 */
@Component
public class EchoHandler implements MessageHandler {

    /** 优先级：整数最大值，永远排在链尾。 */
    private static final int ORDER = Integer.MAX_VALUE;

    @Override
    public IntentType intentType() {
        return IntentType.UNKNOWN;
    }

    @Override
    public boolean supports(InboundMessage message) {
        // 兜底：认领一切走到这里的消息。
        return true;
    }

    @Override
    public HandleResult handle(InboundMessage message) {
        String original = message.getContent() == null ? "(空内容)" : message.getContent();
        String reply = "[echo] " + original;
        return HandleResult.replied(OutboundMessage.textReply(message, reply));
    }

    @Override
    public int order() {
        return ORDER;
    }
}