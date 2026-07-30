package com.zero.ai.agentstudy.day12longrunningagent.queue;

import java.util.Optional;

/**
 * 任务队列抽象。
 *
 * <p>面向接口而非实现：本教程提供 {@link InMemoryTaskQueue} 内存实现，
 * 生产环境可无缝替换为 Redis List / RabbitMQ / Kafka / AWS SQS 等。</p>
 *
 * <p>队列的两个关键语义：
 * <ol>
 *   <li><b>延迟可见</b>：{@link #poll()} 只返回 {@code visibleAt} 已到期的任务，
 *       这是"定时任务/延迟重试"能在队列层实现的基础。</li>
 *   <li><b>至少一次投递</b>：消费后需显式 {@link #ack(String)} 确认，
 *       否则可（由更完整实现）重新可见，保证任务不丢。本内存版做了简化。</li>
 * </ol>
 */
public interface TaskQueue {

    /** 入队一个任务。 */
    void enqueue(AgentTask task);

    /**
     * 拉取一个当前可见（visibleAt 已到期）的任务；无可见任务时返回空。
     *
     * <p>返回的任务会被标记为"处理中"，直到 ack。</p>
     */
    Optional<AgentTask> poll();

    /** 确认任务处理成功，从队列彻底移除。 */
    void ack(String taskId);

    /**
     * 处理失败，按 delayMillis 延迟后重新可见（用于重试退避）。
     *
     * @param taskId      任务 ID
     * @param delayMillis 延迟毫秒（由 RetryPolicy 计算得出）
     */
    void requeue(String taskId, long delayMillis);

    /** 当前队列中的任务总数（含不可见与处理中）。 */
    int size();
}