package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.application.CommandRegistry;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.port.Command;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@code /help} 命令：自动生成帮助菜单。
 * <p>
 * 遍历 {@link CommandRegistry} 中所有已注册命令，按主名字典序输出
 * 「命令 - 描述」与用法示例。新增任何命令后，帮助菜单自动更新，无需手工维护。
 * <p>
 * 注意：{@code CommandRegistry} 构造期需要收集所有 {@link Command}，
 * 而本命令又依赖注册表，故用 {@link Lazy} 打破构造期循环依赖。
 *
 * @author ZeroAi
 */
@Component
public class HelpCommand implements Command {

    private final CommandRegistry registry;

    public HelpCommand(@Lazy CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String[] aliases() {
        return new String[]{"?", "menu"};
    }

    @Override
    public String description() {
        return "查看所有可用命令";
    }

    @Override
    public String usage() {
        return "/help";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 可用命令（共 ").append(registry.size()).append(" 条）\n\n");
        for (Command command : registry.all()) {
            sb.append("- `/").append(command.name()).append('`');
            String[] aliases = command.aliases();
            if (aliases.length > 0) {
                sb.append("（别名：");
                for (int i = 0; i < aliases.length; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('/').append(aliases[i]);
                }
                sb.append('）');
            }
            sb.append(" — ").append(command.description()).append('\n');
            sb.append("  用法：`").append(command.usage()).append("`\n");
        }
        sb.append("\n> 发送 `/help` 可随时再次查看本菜单。");
        return CommandResult.ok(sb.toString());
    }
}