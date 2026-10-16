package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.port.Command;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.application.SessionService;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.model.ConversationSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code /reset} 命令：重置当前会话上下文。
 * <p>
 * 复用 wechatsession 的 {@link SessionService}：获取当前会话并将其归档 / 重建，
 * 清空多轮对话上下文，使后续对话从「干净上下文」开始。适用于用户切换话题、
 * 上下文串味或希望「重开一局」的场景。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class ResetCommand implements Command {

    private final SessionService sessionService;

    public ResetCommand(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    public String name() {
        return "reset";
    }

    @Override
    public String[] aliases() {
        return new String[]{"clear", "new"};
    }

    @Override
    public String description() {
        return "重置当前会话上下文，开始新的对话";
    }

    @Override
    public String usage() {
        return "/reset";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        // 归档旧会话：调用 reset 使当前会话失效并重建，清空多轮上下文。
        ConversationSession fresh = sessionService.reset(
                context.tenantId(), context.channelType(), context.conversationId());
        log.info("[Command] /reset 会话已重置 key={} tenant={}",
                fresh.sessionKey(), context.tenantId());
        return CommandResult.ok("会话上下文已重置，我们可以开始新的对话啦～");
    }
}