package com.zero.ai.agentstudy.day10planningagent.api;

/**
 * 运行规划任务的入参。
 *
 * @param goal      目标自然语言描述（必填）
 * @param maxSteps  步数护栏（<=0 用默认 15）
 * @param maxReplan 重规划护栏（<0 用默认 3）
 * @param timeoutMs 超时护栏毫秒（<=0 用默认 120000）
 */
public record RunRequest(String goal, int maxSteps, int maxReplan, long timeoutMs) {
}