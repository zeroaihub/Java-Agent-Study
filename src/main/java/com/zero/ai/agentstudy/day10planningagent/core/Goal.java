package com.zero.ai.agentstudy.day10planningagent.core;

/**
 * 用户目标。作为 Planning Agent 的唯一输入。
 *
 * @param description 目标自然语言描述
 * @param maxSteps    步数护栏：整个任务允许执行的最大步数
 * @param maxReplan   重规划护栏：允许重规划的最大次数
 * @param timeoutMs   超时护栏：整个任务的最大耗时（毫秒）
 */
public record Goal(String description, int maxSteps, int maxReplan, long timeoutMs) {

    public Goal {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("目标描述不能为空");
        }
        if (maxSteps <= 0) maxSteps = 15;
        if (maxReplan < 0) maxReplan = 3;
        if (timeoutMs <= 0) timeoutMs = 120_000L;
    }

    /** 便捷工厂方法。 */
    public static Goal of(String description, int maxSteps, int maxReplan, long timeoutMs) {
        return new Goal(description, maxSteps, maxReplan, timeoutMs);
    }
}