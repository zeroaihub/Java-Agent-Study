package com.zero.ai.agentstudy.day10planningagent.context;

/**
 * 一次步骤执行后的观察记录（写入黑板，供反思与后续步骤读取）。
 *
 * @param stepId  被观察的步骤 id
 * @param success 是否成功
 * @param output  成功输出
 * @param error   失败原因
 */
public record Observation(String stepId, boolean success, String output, String error) {
}