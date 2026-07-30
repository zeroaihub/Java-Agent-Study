package com.zero.ai.agentstudy.day12longrunningagent.scheduler;

import com.zero.ai.agentstudy.day12longrunningagent.queue.AgentTask;
import com.zero.ai.agentstudy.day12longrunningagent.queue.DeadLetterQueue;
import com.zero.ai.agentstudy.day12longrunningagent.queue.TaskQueue;
import com.zero.ai.agentstudy.day12longrunningagent.retry.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务调度器（消费循环核心）。
 *
 * <p>把前面几个模块拼装成一条可靠的执行流水线：
 * <pre>
 *   TaskQueue.poll() ──► 按 type 找 Handler ──► handle()
 *                                               │
 *                            成功 ── ack()      │ 抛异常
 *                                               ▼
 *                                   RetryPolicy.canRetry(attempts)?
 *                                     ├── 是 ─► requeue(退避延迟)
 *                                     └── 否 ─► DeadLetterQueue.put()
 * </pre>
 *
 * <p><b>可靠性三板斧全在这一层收敛</b>：至少一次投递（poll/ack）、
 * 指数退避重试（RetryPolicy + requeue）、毒丸隔离（DLQ）。</p>
 *
 * <p>本类只提供"单次 tick"驱动（{@link #tick()}），由 {@link AgentScheduler}
 * 定时调用；如此可测（单测直接调 tick）、可控（不自旋占满 CPU）。</p>
 */
@Service
public class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    private final TaskQueue taskQueue;
    private final DeadLetterQueue deadLetterQueue;
    private final RetryPolicy retryPolicy;

    /** type → handler 路由表，容器启动时由所有 TaskHandler Bean 装配而成。 */
    private final Map<String, TaskHandler> handlers = new ConcurrentHashMap<>();

    public TaskDispatcher(TaskQueue taskQueue,
                          DeadLetterQueue deadLetterQueue,
                          RetryPolicy retryPolicy,
                          List<TaskHandler> handlerBeans) {
        this.taskQueue = taskQueue;
        this.deadLetterQueue = deadLetterQueue;
        this.retryPolicy = retryPolicy;
        // 依赖注入所有 Handler，构建路由表；重复 type 视为配置错误
        Map<String, TaskHandler> map = handlerBeans.stream()
                .collect(Collectors.toMap(
                        TaskHandler::supportType,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "重复的任务类型 handler: " + a.supportType());
                        }));
        this.handlers.putAll(map);
        log.info("[Dispatcher] 已注册 {} 个任务处理器: {}", handlers.size(), handlers.keySet());
    }

    /**
     * 驱动一次调度：尝试从队列取一个到期任务并处理。
     *
     * @return 本次是否真的处理了任务（无可见任务时为 false）
     */
    public boolean tick() {
        Optional<AgentTask> polled = taskQueue.poll();
        if (polled.isEmpty()) {
            return false;
        }
        AgentTask task = polled.get();
        process(task);
        return true;
    }

    /** 处理单个任务的完整"成功/重试/死信"决策。 */
    private void process(AgentTask task) {
        TaskHandler handler = handlers.get(task.getType());
        if (handler == null) {
            // 没有处理器 = 配置/投递错误，直接进死信，避免无限循环
            log.error("[Dispatcher] 找不到任务类型的处理器 type={}, taskId={}",
                    task.getType(), task.getTaskId());
            deadLetterQueue.put(task, "no handler for type: " + task.getType());
            taskQueue.ack(task.getTaskId());
            return;
        }

        try {
            handler.handle(task);
            // 成功：确认并从队列移除
            taskQueue.ack(task.getTaskId());
            log.info("[Dispatcher] 任务成功 taskId={}, type={}, attempts={}",
                    task.getTaskId(), task.getType(), task.getAttempts());
        } catch (Exception ex) {
            onFailure(task, ex);
        }
    }

    /** 失败后的重试/死信决策。 */
    private void onFailure(AgentTask task, Exception ex) {
        // task.attempts 在 poll 时已 +1，代表"已尝试的次数"
        int attempts = task.getAttempts();
        if (retryPolicy.canRetry(attempts)) {
            long delay = retryPolicy.nextDelayMillis(attempts);
            log.warn("[Dispatcher] 任务失败将重试 taskId={}, attempts={}, 退避={}ms, err={}",
                    task.getTaskId(), attempts, delay, ex.toString());
            taskQueue.requeue(task.getTaskId(), delay);
        } else {
            log.error("[Dispatcher] 重试耗尽，投递死信 taskId={}, attempts={}",
                    task.getTaskId(), attempts, ex);
            deadLetterQueue.put(task, ex.toString());
            taskQueue.ack(task.getTaskId());
        }
    }

    /** 当前注册的处理器类型集合（监控/自检用）。 */
    public java.util.Set<String> registeredTypes() {
        return java.util.Collections.unmodifiableSet(handlers.keySet());
    }
}