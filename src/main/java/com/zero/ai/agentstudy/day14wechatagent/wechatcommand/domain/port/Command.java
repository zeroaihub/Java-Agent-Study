package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model.CommandResult;

/**
 * 命令端口（Command Port）。
 * <p>
 * 每一个可执行命令都是一个独立的策略实现，遵循统一契约：
 * <ul>
 *     <li>{@link #name()}：命令名（不含前缀 {@code /}，小写），是注册表的唯一键。</li>
 *     <li>{@link #aliases()}：命令别名列表，如 {@code help} 的别名 {@code ?}。</li>
 *     <li>{@link #description()}：一行用途描述，供 {@code /help} 自动生成帮助。</li>
 *     <li>{@link #usage()}：用法示例，如 {@code /book room=301 time=14:00}。</li>
 *     <li>{@link #execute(CommandContext)}：执行命令并返回 {@link CommandResult}。</li>
 * </ul>
 * 领域端口不依赖任何 Spring 注解；具体实现放在 infrastructure 层并标注
 * {@code @Component}，由 {@code CommandRegistry} 集合注入自动收集，实现「新增命令零配置」。
 *
 * @author ZeroAi
 */
public interface Command {

    /**
     * 命令名（唯一键，不含前缀，建议小写）。
     *
     * @return 命令名
     */
    String name();

    /**
     * 命令别名（默认无）。
     *
     * @return 别名数组
     */
    default String[] aliases() {
        return new String[0];
    }

    /**
     * 命令用途描述（供 /help 展示）。
     *
     * @return 一行描述
     */
    String description();

    /**
     * 用法示例（供 /help 与参数错误时提示）。
     *
     * @return 用法字符串
     */
    default String usage() {
        return "/" + name();
    }

    /**
     * 执行命令。
     * <p>
     * 实现内部负责：参数校验 → 权限检查 → 业务执行 → 结果格式化。
     * 出现可预期错误（参数非法、无权限）应返回 {@link CommandResult#error(String)}，
     * 而非抛异常，以便向用户给出友好提示。
     *
     * @param context 命令执行上下文
     * @return 执行结果
     */
    CommandResult execute(CommandContext context);
}