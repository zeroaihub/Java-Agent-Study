package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.ParsedCommand;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.port.Command;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 命令分发器（Command Dispatcher）。
 * <p>
 * 命令系统的应用层入口，串起完整执行链：
 * <pre>
 *   原始文本 --parse--> ParsedCommand --lookup--> Command --execute--> CommandResult
 * </pre>
 * 职责：
 * <ul>
 *     <li>解析命令文本（委托 {@link CommandParser}）。</li>
 *     <li>查注册表定位命令（委托 {@link CommandRegistry}）。</li>
 *     <li>执行命令并统一异常兜底：任何未捕获异常都转为 {@link CommandResult#error}，
 *         保证「一条坏命令不会拖垮整个 Agent」。</li>
 *     <li>未知命令给出友好提示并引导 {@code /help}。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Slf4j
@Service
public class CommandDispatcher {

    private final CommandParser parser;
    private final CommandRegistry registry;

    public CommandDispatcher(CommandParser parser, CommandRegistry registry) {
        this.parser = parser;
        this.registry = registry;
    }

    /**
     * 判断文本是否为命令。
     *
     * @param text 文本
     * @return 是命令返回 {@code true}
     */
    public boolean isCommand(String text) {
        return parser.isCommand(text);
    }

    /**
     * 分发并执行入站消息中的命令。
     *
     * @param message 入站消息（其文本应为命令）
     * @return 执行结果
     */
    public CommandResult dispatch(InboundMessage message) {
        return dispatch(message, message.getContent());
    }

    /**
     * 分发并执行指定命令文本。
     *
     * @param message 入站消息（用于构造上下文）
     * @param text    命令文本
     * @return 执行结果
     */
    public CommandResult dispatch(InboundMessage message, String text) {
        ParsedCommand parsed = parser.parse(text);
        if (parsed.name().isEmpty()) {
            return CommandResult.error("请输入有效命令，例如 `/help` 查看可用命令。");
        }

        Optional<Command> found = registry.find(parsed.name());
        if (found.isEmpty()) {
            log.debug("[Command] 未知命令：{}", parsed.name());
            return CommandResult.error("未知命令：`/" + parsed.name()
                    + "`。发送 `/help` 查看所有可用命令。");
        }

        Command command = found.get();
        CommandContext context = CommandContext.of(message, parsed);
        long start = System.nanoTime();
        try {
            CommandResult result = command.execute(context);
            log.info("[Command] 执行 /{} 完成 status={} cost={}ms tenant={}",
                    command.name(), result.status(),
                    (System.nanoTime() - start) / 1_000_000, context.tenantId());
            return result == null ? CommandResult.silent() : result;
        } catch (Exception ex) {
            log.error("[Command] 执行 /{} 异常 tenant={}",
                    command.name(), context.tenantId(), ex);
            return CommandResult.error("命令执行出错：" + safeMessage(ex)
                    + "。请稍后重试或联系管理员。");
        }
    }

    private String safeMessage(Exception ex) {
        String msg = ex.getMessage();
        return (msg == null || msg.isBlank()) ? ex.getClass().getSimpleName() : msg;
    }
}