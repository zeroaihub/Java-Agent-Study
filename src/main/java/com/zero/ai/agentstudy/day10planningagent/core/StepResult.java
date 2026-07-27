package com.zero.ai.agentstudy.day10planningagent.core;

/**
 * 步骤执行结果。执行器返回此对象，绝不向主循环抛异常（失败也封装为 failure）。
 *
 * @param success 是否成功
 * @param output  成功时的输出内容
 * @param error   失败时的错误信息
 */
public record StepResult(boolean success, String output, String error) {

    public static StepResult success(String output) {
        return new StepResult(true, output == null ? "" : output, null);
    }

    public static StepResult failure(String error) {
        return new StepResult(false, null, error == null ? "unknown error" : error);
    }
}