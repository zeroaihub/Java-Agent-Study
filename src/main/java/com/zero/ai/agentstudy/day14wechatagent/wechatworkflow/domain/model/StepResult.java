package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model;

/**
 * 单步执行结果（值对象）。
 *
 * <p>{@code StepExecutor} 执行一个步骤后，用 {@code StepResult} 回报三种结局之一：</p>
 * <ul>
 *     <li>{@link #success(String)} —— 执行成功，携带产出摘要。</li>
 *     <li>{@link #failure(String)} —— 执行失败，携带错误信息（引擎据此决定是否重试）。</li>
 *     <li>{@link #skip(String)} —— 条件不满足，跳过该步骤。</li>
 * </ul>
 *
 * <p>把「执行结局」建模为不可变值对象，而非抛异常，是为了让引擎能<strong>统一、显式</strong>
 * 地处理成功/失败/跳过三态，而不必在到处 try-catch。异常仍可作为 failure 的补充信号。</p>
 *
 * @param outcome 结局类型
 * @param message 产出摘要或错误信息
 * @author ZeroHub AI Agent Platform
 */
public record StepResult(Outcome outcome, String message) {

    /** 执行结局枚举。 */
    public enum Outcome {
        /** 成功。 */
        SUCCESS,
        /** 失败。 */
        FAILURE,
        /** 跳过。 */
        SKIP
    }

    /**
     * 紧凑构造器：非空校验。
     */
    public StepResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
        message = message == null ? "" : message;
    }

    /**
     * 构造成功结果。
     *
     * @param message 产出摘要
     * @return 成功结果
     */
    public static StepResult success(String message) {
        return new StepResult(Outcome.SUCCESS, message);
    }

    /**
     * 构造失败结果。
     *
     * @param message 错误信息
     * @return 失败结果
     */
    public static StepResult failure(String message) {
        return new StepResult(Outcome.FAILURE, message);
    }

    /**
     * 构造跳过结果。
     *
     * @param message 跳过原因
     * @return 跳过结果
     */
    public static StepResult skip(String message) {
        return new StepResult(Outcome.SKIP, message);
    }

    /** @return 是否成功 */
    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    /** @return 是否失败 */
    public boolean isFailure() {
        return outcome == Outcome.FAILURE;
    }

    /** @return 是否跳过 */
    public boolean isSkip() {
        return outcome == Outcome.SKIP;
    }
}