package com.zero.ai.agentstudy.day12longrunningagent.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 调度器（定时驱动 + 定时投递入口）。
 *
 * <p>两类"定时"在这里汇合：
 * <ol>
 *   <li><b>消费驱动</b>：以固定频率调用 {@link TaskDispatcher#tick()}，
 *       把队列中到期的任务源源不断地推给处理器。这是队列"活起来"的心跳。</li>
 *   <li><b>业务定时</b>：如"每天 9 点检查 GitHub Trending"，用 cron 表达式
 *       在指定时刻向队列投递一个任务（见最终实战 GithubTrendingAgent）。</li>
 * </ol>
 *
 * <p>用 {@link Scheduled} 而非手写 while(true) 自旋，原因：
 * <ul>
 *   <li>交给 Spring 的调度线程池统一管理，避免裸线程泄漏；</li>
 *   <li>fixedDelay 保证上一次执行完再等间隔，天然背压、不会堆叠；</li>
 *   <li>可通过配置动态调整频率，无需改代码。</li>
 * </ul>
 *
 * <p>需要在配置类上开启 {@code @EnableScheduling} 才会生效
 * （见 Day12RuntimeConfig）。</p>
 */
@Component
public class AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduler.class);

    /** 单次心跳最多连续处理的任务数，防止某一 tick 长时间霸占调度线程。 */
    private static final int MAX_DRAIN_PER_TICK = 50;

    private final TaskDispatcher dispatcher;

    /** 运行开关：可在运维时优雅暂停消费而不停进程。 */
    private final AtomicBoolean running = new AtomicBoolean(true);

    public AgentScheduler(TaskDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 消费心跳：默认每 500ms 驱动一轮。
     *
     * <p>每轮最多连续排空 {@link #MAX_DRAIN_PER_TICK} 个到期任务，
     * 既保证吞吐，又给调度线程留出喘息，避免饿死其它定时任务。</p>
     */
    @Scheduled(fixedDelayString = "${zero.agent.scheduler.tick-delay-ms:500}")
    public void heartbeat() {
        if (!running.get()) {
            return;
        }
        int processed = 0;
        try {
            while (processed < MAX_DRAIN_PER_TICK && dispatcher.tick()) {
                processed++;
            }
        } catch (Exception ex) {
            // 心跳线程绝不能因单次异常而死掉
            log.error("[Scheduler] 心跳处理异常", ex);
        }
        if (processed > 0) {
            log.debug("[Scheduler] 本轮心跳处理任务数={}", processed);
        }
    }

    /** 优雅暂停消费（保留进程，队列继续接收但不出队）。 */
    public void pause() {
        running.set(false);
        log.info("[Scheduler] 已暂停消费");
    }

    /** 恢复消费。 */
    public void resume() {
        running.set(true);
        log.info("[Scheduler] 已恢复消费");
    }

    public boolean isRunning() {
        return running.get();
    }
}