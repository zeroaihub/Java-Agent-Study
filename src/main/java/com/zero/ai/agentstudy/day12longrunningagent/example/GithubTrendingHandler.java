package com.zero.ai.agentstudy.day12longrunningagent.example;

import com.zero.ai.agentstudy.day12longrunningagent.event.AgentEvent;
import com.zero.ai.agentstudy.day12longrunningagent.event.EventBus;
import com.zero.ai.agentstudy.day12longrunningagent.queue.AgentTask;
import com.zero.ai.agentstudy.day12longrunningagent.runtime.AgentRuntime;
import com.zero.ai.agentstudy.day12longrunningagent.scheduler.TaskHandler;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * GitHub Trending Agent —— 全书能力的综合实战落地。
 *
 * <p>这是本课程的"毕业设计"：一个每天定时运行的长任务，把前十一章所有零件串成
 * 真实业务流水线：</p>
 * <pre>
 *   Step 0  登录 GitHub          (LOGIN)
 *   Step 1  抓取 Trending 列表    (FETCH)
 *   Step 2  用 LLM 生成中文总结    (SUMMARIZE)
 *   Step 3  推送到企业微信         (NOTIFY)
 *   Step 4  完成，等待明天         (COMPLETE -> WAITING/COMPLETED)
 * </pre>
 *
 * <p><b>关于外部依赖：</b>真实项目里，登录/抓取会用 Playwright，总结会调 Spring AI，
 * 推送会发 HTTP。为保证本教程<b>零外部依赖即可跑通并验证流程正确性</b>，这里用
 * <b>桩实现（Stub）</b> 模拟每一步的耗时与产出。把桩换成真实实现，业务骨架不变——
 * 这正是"面向接口/面向流程编程"的价值：<b>流程是稳定的，实现是可替换的</b>。</p>
 *
 * <p>本 Handler 以 {@code task.type == "github-trending-step"} 注册到调度器，
 * 每次 {@code handle} 只推进<b>一步</b>，然后把"下一步任务"重新入队——
 * 这种"单步驱动 + 自我续投"的模式，让长流程天然具备可检查点、可恢复、可重试的能力。</p>
 */
@Component
public class GithubTrendingHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(GithubTrendingHandler.class);

    /** 本 Agent 的类型标识。 */
    public static final String AGENT_TYPE = "github-trending";
    /** 本 Handler 处理的任务类型。 */
    public static final String TASK_TYPE = "github-trending-step";

    /** 流水线步骤名（stepIndex 即数组下标）。 */
    private static final String[] STEPS = {"LOGIN", "FETCH", "SUMMARIZE", "NOTIFY"};

    private final AgentRuntime runtime;
    private final EventBus eventBus;
    /** 注入模拟外部世界的桩，真实环境替换为 Playwright/SpringAI/HTTP 客户端。 */
    private final TrendingStubClient stub;

    public GithubTrendingHandler(AgentRuntime runtime, EventBus eventBus, TrendingStubClient stub) {
        this.runtime = runtime;
        this.eventBus = eventBus;
        this.stub = stub;
    }

    @Override
    public String supportType() {
        return TASK_TYPE;
    }

    @Override
    public void handle(AgentTask task) throws Exception {
        String sessionId = task.getSessionId();
        AgentSession session = runtime.find(sessionId)
                .orElseThrow(() -> new IllegalStateException("会话不存在: " + sessionId));

        int step = session.getContext().getStepIndex();
        if (step >= STEPS.length) {
            // 已越界：所有步骤完成，收尾
            finish(session);
            return;
        }

        String stepName = STEPS[step];
        log.info("[GithubTrending] sessionId={} 执行 Step{}={}", sessionId, step, stepName);

        // 按步骤分派到对应的桩逻辑（真实环境即为真实调用）
        switch (stepName) {
            case "LOGIN" -> {
                stub.login();
                session.getContext().put("loggedIn", true);
            }
            case "FETCH" -> {
                List<String> repos = stub.fetchTrending();
                session.getContext().put("repos", repos);
                log.info("[GithubTrending] 抓到 {} 个 Trending 项目", repos.size());
            }
            case "SUMMARIZE" -> {
                @SuppressWarnings("unchecked")
                List<String> repos = (List<String>) session.getContext().get("repos");
                String summary = stub.summarize(repos);
                session.getContext().put("summary", summary);
            }
            case "NOTIFY" -> {
                String summary = session.getContext().get("summary");
                stub.notifyWeCom(summary);
                eventBus.publish(AgentEvent.of("NOTIFY_SENT", sessionId, "企业微信推送完成"));
            }
            default -> throw new IllegalStateException("未知步骤: " + stepName);
        }

        // 本步成功：推进进度并打检查点（崩溃时最多丢当前这一步）
        int next = runtime.advanceAndCheckpoint(session);
        // 广播任务成功事件，驱动监控指标
        eventBus.publish(AgentEvent.of("TASK_SUCCESS", sessionId, stepName));

        if (next >= STEPS.length) {
            finish(session);
        } else {
            // 自我续投：把"下一步"重新入队，交给调度器下一拍执行
            requeueNextStep(session);
        }
    }

    /** 全部步骤完成，会话转 COMPLETED。 */
    private void finish(AgentSession session) {
        log.info("[GithubTrending] sessionId={} 全部步骤完成，简报已送达", session.getSessionId());
        runtime.complete(session);
    }

    /**
     * 把下一步任务重新入队。这里通过发布事件的方式解耦——实际入队动作由
     * {@link TrendingScheduler} 或调用方完成。为教学清晰，此处保留一个钩子事件。
     */
    private void requeueNextStep(AgentSession session) {
        eventBus.publish(AgentEvent.of("NEXT_STEP_READY", session.getSessionId(),
                session.getContext().getStepIndex()));
    }
}