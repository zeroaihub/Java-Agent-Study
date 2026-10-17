package com.zero.ai.agentstudy.day17codeagent.observability;

/**
 * Agent 链路上的一个步骤。
 *
 * <p>为什么每一步都要记：Code Agent 一次任务会经过十几步，
 * 出问题时「失败」这个结论本身没有任何信息量——
 * 必须能立刻回答<b>是哪一步坏的、耗了多久、花了多少钱</b>。</p>
 *
 * @param name      步骤名
 * @param status    状态
 * @param elapsedMs 耗时
 * @param tokens    本步 token 消耗（无则为 0）
 * @param detail    关键信息摘要（命中文件数、错误数等）
 * @author ZeroAi
 */
public record AgentStep(
        String name,
        Status status,
        long elapsedMs,
        int tokens,
        String detail
) {

    public enum Status {
        SUCCESS("成功"), FAILED("失败"), SKIPPED("跳过"), DEGRADED("降级");

        private final String display;

        Status(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    @Override
    public String toString() {
        return String.format("%-14s %-4s %6dms %6d token  %s",
                name, status.display(), elapsedMs, tokens, detail == null ? "" : detail);
    }
}
