package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.port.Command;
import org.springframework.stereotype.Component;

/**
 * {@code /whoami} 命令：查看当前身份与会话信息。
 * <p>
 * 回显当前请求的租户、用户、渠道与会话标识，便于用户 / 排障时确认身份上下文。
 * 完整用户画像（昵称、部门、偏好、历史统计）将在后续章节接入用户画像 / Memory
 * 模块后补全，此处先输出上下文可得的基础身份信息。
 *
 * @author ZeroAi
 */
@Component
public class WhoAmICommand implements Command {

    @Override
    public String name() {
        return "whoami";
    }

    @Override
    public String[] aliases() {
        return new String[]{"me"};
    }

    @Override
    public String description() {
        return "查看当前身份与会话信息";
    }

    @Override
    public String usage() {
        return "/whoami";
    }

    @Override
    public CommandResult execute(CommandContext context) {
        // TODO 后续接入用户画像 / Memory 模块，补全昵称、部门、偏好与历史统计。
        String reply = "### 当前身份\n"
                + "- 租户：`" + context.tenantId() + "`\n"
                + "- 用户：`" + context.fromUserId() + "`\n"
                + "- 渠道：`" + context.channelType() + "`\n"
                + "- 会话：`" + context.conversationId() + "`\n\n"
                + "> 更完整的用户画像将在用户画像 / Memory 模块接入后展示。";
        return CommandResult.ok(reply);
    }
}