package com.zero.ai.agentstudy.day12longrunningagent.queue;

import java.time.Instant;

/**
 * 死信记录（Dead Letter）。
 *
 * <p>当一个任务重试耗尽（超过 {@code maxRetries}）仍然失败，它不应被静默丢弃，
 * 也不应无限重试拖垮系统。正确做法是投递到 <b>死信队列（DLQ, Dead Letter Queue）</b>：
 * <ul>
 *   <li>把"毒丸消息"（poison message）从主流程隔离出去，避免堵塞正常任务；</li>
 *   <li>保留完整上下文（原任务 + 最后错误 + 失败时间），供人工排查或后续重放；</li>
 *   <li>可对接告警：DLQ 有新增即通知值班工程师。</li>
 * </ul>
 *
 * <p>这是一个不可变记录，用 record 承载。</p>
 *
 * @param task        原始任务
 * @param reason      失败原因（最后一次异常摘要）
 * @param totalAttempts 累计尝试次数
 * @param deadAt      进入死信队列的时间
 */
public record DeadLetter(
        AgentTask task,
        String reason,
        int totalAttempts,
        Instant deadAt
) {

    public DeadLetter {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        if (deadAt == null) {
            deadAt = Instant.now();
        }
    }

    /** 便捷工厂：以当前时间创建死信。 */
    public static DeadLetter of(AgentTask task, String reason) {
        return new DeadLetter(task, reason, task.getAttempts(), Instant.now());
    }
}