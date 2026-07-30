package com.zero.ai.agentstudy.day12longrunningagent.queue;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * 任务队列的内存实现。
 *
 * <p>核心数据结构是一个 <b>按 visibleAt 排序的优先级队列</b>（小根堆）：
 * 队头永远是"最早该被执行"的任务。poll 时先看队头是否已到期，
 * 到期则取出、未到期则返回空——这就是延迟/定时任务的最小可用实现。</p>
 *
 * <p>并发：{@link PriorityBlockingQueue} 本身线程安全；另用一个
 * {@link ConcurrentHashMap} 维护 taskId → task 索引，便于 ack/requeue 按 ID 操作。</p>
 *
 * <p>局限（相对生产队列）：进程重启即丢失、无持久化、无消费者组、
 * 无"处理中超时自动重投"。生产请换 Redis/RabbitMQ/Kafka。</p>
 */
@Component
public class InMemoryTaskQueue implements TaskQueue {

    /** 按可见时间升序的优先级队列（小根堆）。 */
    private final PriorityBlockingQueue<AgentTask> queue =
            new PriorityBlockingQueue<>(64, Comparator.comparing(AgentTask::getVisibleAt));

    /** taskId → task 索引，支持 ack/requeue 快速定位。 */
    private final ConcurrentHashMap<String, AgentTask> index = new ConcurrentHashMap<>();

    @Override
    public void enqueue(AgentTask task) {
        index.put(task.getTaskId(), task);
        queue.offer(task);
    }

    @Override
    public Optional<AgentTask> poll() {
        Instant now = Instant.now();
        AgentTask head = queue.peek();
        // 队头未到期 => 当前无可消费任务
        if (head == null || !head.isVisible(now)) {
            return Optional.empty();
        }
        AgentTask task = queue.poll();
        if (task == null) {
            return Optional.empty();
        }
        // 取出即视为一次尝试
        task.incrementAttempts();
        // 仍保留在 index 中（处于"处理中"），等待 ack 或 requeue
        return Optional.of(task);
    }

    @Override
    public void ack(String taskId) {
        // 处理成功：从索引彻底移除
        index.remove(taskId);
    }

    @Override
    public void requeue(String taskId, long delayMillis) {
        AgentTask task = index.get(taskId);
        if (task == null) {
            // 已被 ack 或从未入队，忽略
            return;
        }
        // 延迟后重新可见，放回堆中参与排序
        task.setVisibleAt(Instant.now().plusMillis(Math.max(delayMillis, 0)));
        queue.offer(task);
    }

    @Override
    public int size() {
        // 以索引为准：queue 中可能残留已 ack 的旧引用（简化实现的代价）
        return index.size();
    }

    /** 测试/监控用：清空队列。 */
    public void clear() {
        queue.clear();
        index.clear();
    }
}