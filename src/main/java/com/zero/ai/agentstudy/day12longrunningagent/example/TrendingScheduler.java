package com.zero.ai.agentstudy.day12longrunningagent.example;

import com.zero.ai.agentstudy.day12longrunningagent.event.AgentEvent;
import com.zero.ai.agentstudy.day12longrunningagent.event.EventListener;
import com.zero.ai.agentstudy.day12longrunningagent.queue.AgentTask;
import com.zero.ai.agentstudy.day12longrunningagent.queue.TaskQueue;
import com.zero.ai.agentstudy.day12longrunningagent.runtime.AgentRuntime;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Trending Agent 的"触发器 + 续投器"。
 *
 * <p>它承担两个职责，正好对应长生命周期 Agent 的两种驱动力：</p>
 * <ol>
 *   <li><b>定时触发（Cron）</b>：每天 09:00 自动开启一次新会话，入队第一步任务。
 *       这是"周期任务"的入口——机器自己醒来干活，无需人工点按钮。</li>
 *   <li><b>事件续投（Event Driven）</b>：作为 {@link EventListener} 订阅
 *       {@code NEXT_STEP_READY} 事件——每当 Handler 完成一步就发此事件，
 *       本类据此把"下一步任务"入队。这样长流程被拆成一步步、由事件驱动地推进，
 *       天然可检查点、可恢复、可限流。</li>
 * </ol>
 *
 * <p>把"触发"与"执行"分离（本类只管入队，真正执行在 {@link GithubTrendingHandler}），
 * 是调度系统的经典解耦：调度器只决定"何时该做"，执行器只关心"怎么做"。</p>
 */
@Component
public class TrendingScheduler implements EventListener {

    private static final Logger log = LoggerFactory.getLogger(TrendingScheduler.class);

    private final AgentRuntime runtime;
    private final TaskQueue taskQueue;

    public TrendingScheduler(AgentRuntime runtime, TaskQueue taskQueue) {
        this.runtime = runtime;
        this.taskQueue = taskQueue;
    }

    /**
     * 每天 09:00 触发一次。cron 可通过配置覆盖，默认每天上午 9 点。
     *
     * <p>教学演示：也可把 cron 改为 {@code "0/30 * * * * *"}（每 30 秒）快速观察全流程。</p>
     */
    @Scheduled(cron = "${zero.agent.trending.cron:0 0 9 * * *}")
    public void triggerDaily() {
        AgentSession session = runtime.createSession(GithubTrendingHandler.AGENT_TYPE);
        runtime.start(session);
        enqueueStep(session);
        log.info("[TrendingScheduler] 每日触发：已开启会话 sessionId={} 并入队首步", session.getSessionId());
    }

    /**
     * 手动触发一次（供 REST API / 测试调用），返回新会话 ID。
     */
    public String triggerOnce() {
        AgentSession session = runtime.createSession(GithubTrendingHandler.AGENT_TYPE);
        runtime.start(session);
        enqueueStep(session);
        return session.getSessionId();
    }

    /** 订阅"下一步就绪"事件。 */
    @Override
    public String interestedType() {
        return "NEXT_STEP_READY";
    }

    /**
     * 收到"下一步就绪"事件后，把下一步任务入队。
     *
     * <p>注意：这里天然实现了"事件驱动的流程推进"——Handler 完成一步 → 发事件 →
     * 本类入队下一步 → 调度器拉取执行 → 又完成一步……直到流程结束。</p>
     */
    @Override
    public void onEvent(AgentEvent event) {
        String sessionId = event.sessionId();
        runtime.find(sessionId).ifPresent(session -> {
            if (session.getState().isActive()) {
                enqueueStep(session);
                log.debug("[TrendingScheduler] 事件驱动续投：sessionId={} 入队下一步", sessionId);
            }
        });
    }

    /** 入队一个"执行当前 stepIndex"的任务。 */
    private void enqueueStep(AgentSession session) {
        AgentTask task = AgentTask.immediate(
                session.getSessionId(),
                GithubTrendingHandler.TASK_TYPE,
                "step=" + session.getContext().getStepIndex());
        taskQueue.enqueue(task);
    }
}