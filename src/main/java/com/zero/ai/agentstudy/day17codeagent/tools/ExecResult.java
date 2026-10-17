package com.zero.ai.agentstudy.day17codeagent.tools;

/**
 * 命令执行结果。
 *
 * <p>注意 {@code timedOut} 与 {@code exitCode} 是<b>两个独立维度</b>：超时被杀掉时
 * exitCode 一般是 137/1，光看 exitCode 无法区分「编译真的报错」和「被超时杀掉」。
 * 区分不了就会导致自修复循环把超时当成语法错误反复重试，白烧一轮预算。</p>
 *
 * @param ok        是否成功退出（exitCode == 0 且未超时）
 * @param exitCode  进程退出码，被杀为 null
 * @param stdout    标准输出（已截断到上限）
 * @param stderr    标准错误（已截断到上限）
 * @param elapsedMs 耗时毫秒
 * @param timedOut  是否因超时被杀
 * @param message   说明（失败原因 / 摘要）
 * @author ZeroAi
 */
public record ExecResult(
        boolean ok,
        Integer exitCode,
        String stdout,
        String stderr,
        long elapsedMs,
        boolean timedOut,
        String message
) {

    public static ExecResult success(String stdout, long elapsedMs) {
        return new ExecResult(true, 0, truncate(stdout), "", elapsedMs, false, "执行成功");
    }

    public static ExecResult failure(int exitCode, String stdout, String stderr, long elapsedMs) {
        return new ExecResult(false, exitCode, truncate(stdout), truncate(stderr),
                elapsedMs, false, "进程以非 0 退出码结束：exitCode=" + exitCode);
    }

    public static ExecResult timeout(long elapsedMs, String partialStdout) {
        return new ExecResult(false, null, truncate(partialStdout), "",
                elapsedMs, true, "执行超时已被强制终止");
    }

    public static ExecResult denied(String reason) {
        return new ExecResult(false, null, "", reason, 0L, false, "命令被安全策略拒绝：" + reason);
    }

    /** 给 LLM 看的合并输出。 */
    public String combinedOutput() {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        if (out.isBlank()) {
            return err.trim();
        }
        if (err.isBlank()) {
            return out.trim();
        }
        return out.trim() + "\n" + err.trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 4000 ? s.substring(0, 4000) + "\n...(输出已截断)" : s;
    }

    @Override
    public String toString() {
        return "ExecResult{ok=" + ok + ", exitCode=" + exitCode + ", timedOut=" + timedOut
                + ", elapsedMs=" + elapsedMs + ", message=" + message + "}";
    }
}
