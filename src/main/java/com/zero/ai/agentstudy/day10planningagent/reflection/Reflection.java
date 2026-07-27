package com.zero.ai.agentstudy.day10planningagent.reflection;

/**
 * 反思结果：裁决 + 人类可读的理由（用于轨迹与调试）。
 *
 * @param verdict 下一步动作裁决
 * @param reason  给出该裁决的原因说明
 */
public record Reflection(Verdict verdict, String reason) {

    public static Reflection cont(String reason) {
        return new Reflection(Verdict.CONTINUE, reason);
    }

    public static Reflection retry(String reason) {
        return new Reflection(Verdict.RETRY_STEP, reason);
    }

    public static Reflection replan(String reason) {
        return new Reflection(Verdict.REPLAN, reason);
    }

    public static Reflection abort(String reason) {
        return new Reflection(Verdict.ABORT, reason);
    }
}