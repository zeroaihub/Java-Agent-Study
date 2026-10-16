package com.zero.ai.agentstudy.day14wechatagent.wechatcommand.domain.model;

/**
 * 命令执行结果（值对象）。
 * <p>
 * 统一表达命令执行的三种结局，供上层（CommandDispatcher / CommandHandler）
 * 转换为对外回复：
 * <ul>
 *     <li>{@link #ok(String)}：执行成功，携带 Markdown 文本回复。</li>
 *     <li>{@link #silent()}：执行成功但无需回复（如后台动作已异步触发）。</li>
 *     <li>{@link #error(String)}：执行失败（参数非法 / 无权限 / 内部异常），携带错误提示。</li>
 * </ul>
 *
 * @param status  结果状态
 * @param message 回复或错误文本（{@code SILENT} 时为空）
 * @author ZeroAi
 */
public record CommandResult(Status status, String message) {

    /**
     * 命令执行状态。
     */
    public enum Status {
        /** 成功且有回复。 */
        OK,
        /** 成功但无回复。 */
        SILENT,
        /** 执行失败。 */
        ERROR
    }

    public CommandResult {
        message = message == null ? "" : message;
    }

    /**
     * 成功并携带回复文本。
     *
     * @param message Markdown 回复
     * @return 结果
     */
    public static CommandResult ok(String message) {
        return new CommandResult(Status.OK, message);
    }

    /**
     * 成功但无需回复。
     *
     * @return 结果
     */
    public static CommandResult silent() {
        return new CommandResult(Status.SILENT, "");
    }

    /**
     * 执行失败。
     *
     * @param message 错误提示
     * @return 结果
     */
    public static CommandResult error(String message) {
        return new CommandResult(Status.ERROR, message);
    }

    /**
     * 是否执行成功（OK 或 SILENT）。
     *
     * @return 成功返回 {@code true}
     */
    public boolean isSuccess() {
        return status == Status.OK || status == Status.SILENT;
    }

    /**
     * 是否需要向用户回复文本。
     *
     * @return 有回复返回 {@code true}
     */
    public boolean hasReply() {
        return status != Status.SILENT && !message.isEmpty();
    }
}