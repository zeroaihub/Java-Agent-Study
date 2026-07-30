package com.zero.ai.agentstudy.day12longrunningagent.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 死信队列（内存实现）。
 *
 * <p>职责单一：接收重试耗尽的任务，落地保存，并留出人工重放的口子。
 * 生产环境通常用独立的 Kafka topic / RabbitMQ DLX / 数据库表承载，
 * 并配置监控告警（DLQ size > 0 即报警）。</p>
 */
@Component
public class DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueue.class);

    /** 读多写少 + 遍历安全，用 CopyOnWriteArrayList。 */
    private final List<DeadLetter> letters = new CopyOnWriteArrayList<>();

    /** 投递一条死信。 */
    public void put(DeadLetter letter) {
        letters.add(letter);
        log.error("[DLQ] 任务进入死信队列 taskId={}, sessionId={}, attempts={}, reason={}",
                letter.task().getTaskId(),
                letter.task().getSessionId(),
                letter.totalAttempts(),
                letter.reason());
    }

    /** 便捷方法：由任务与原因构造死信并投递。 */
    public void put(AgentTask task, String reason) {
        put(DeadLetter.of(task, reason));
    }

    /** 查看当前所有死信（返回快照，避免外部修改内部列表）。 */
    public List<DeadLetter> findAll() {
        return List.copyOf(letters);
    }

    /** 死信数量——监控告警的关键指标。 */
    public int size() {
        return letters.size();
    }

    /** 人工处理完成后移除某条死信（按 taskId）。 */
    public boolean remove(String taskId) {
        return letters.removeIf(dl -> dl.task().getTaskId().equals(taskId));
    }
}