package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.application.CommandDispatcher;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.MessageType;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.OutboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.HandleResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model.IntentType;
import com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.port.MessageHandler;
import org.springframework.stereotype.Component;

/**
 * 命令处理器（责任链最高优先级）。
 * <p>
 * 识别以斜杠 {@code /} 开头的文本消息（如 {@code /help}、{@code /reset}），
 * 委托 wechatcommand 的 {@link CommandDispatcher} 完成真正的解析、查表、执行与
 * 结果格式化，随后按 {@link CommandResult} 状态转换为对外回复：
 * <ul>
 *     <li>{@code OK}：Markdown 回复。</li>
 *     <li>{@code ERROR}：文本回复（错误提示）。</li>
 *     <li>{@code SILENT}：已处理但无回复。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Component
public class CommandHandler implements MessageHandler {

    /** 优先级：命令最高，先于多模态与闲聊。 */
    private static final int ORDER = 10;

    private final CommandDispatcher commandDispatcher;

    public CommandHandler(CommandDispatcher commandDispatcher) {
        this.commandDispatcher = commandDispatcher;
    }

    @Override
    public IntentType intentType() {
        return IntentType.COMMAND;
    }

    @Override
    public boolean supports(InboundMessage message) {
        if (message.getMessageType() != MessageType.TEXT) {
            return false;
        }
        return commandDispatcher.isCommand(message.getContent());
    }

    @Override
    public HandleResult handle(InboundMessage message) {
        CommandResult result = commandDispatcher.dispatch(message);
        return switch (result.status()) {
            case OK -> HandleResult.replied(
                    OutboundMessage.markdownReply(message, result.message()));
            case ERROR -> HandleResult.replied(
                    OutboundMessage.textReply(message, result.message()));
            case SILENT -> HandleResult.handledSilently();
        };
    }

    @Override
    public int order() {
        return ORDER;
    }
}