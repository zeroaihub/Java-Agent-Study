package com.zero.ai.agentstudy.day12longrunningagent.api;

import com.zero.ai.agentstudy.day12longrunningagent.example.TrendingScheduler;
import com.zero.ai.agentstudy.day12longrunningagent.monitor.AgentMetrics;
import com.zero.ai.agentstudy.day12longrunningagent.queue.DeadLetter;
import com.zero.ai.agentstudy.day12longrunningagent.queue.DeadLetterQueue;
import com.zero.ai.agentstudy.day12longrunningagent.queue.TaskQueue;
import com.zero.ai.agentstudy.day12longrunningagent.runtime.AgentRuntime;
import com.zero.ai.agentstudy.day12longrunningagent.scheduler.AgentScheduler;
import com.zero.ai.agentstudy.day12longrunningagent.session.AgentSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Day12 Long Running Agent 的对外运维 / 观测门面（REST API）。
 *
 * <p>一台永不停歇的机器，光会跑还不够——运维者需要能<b>看得见、点得动</b>：
 * 手动触发一次任务、查询某个会话跑到哪一步、查看实时指标仪表盘、
 * 检查死信队列有没有堆积、必要时暂停/恢复消费。本控制器把这些能力
 * 用最薄的一层 HTTP 暴露出来，方便本地演示与生产运维。</p>
 *
 * <h3>端点一览</h3>
 * <pre>
 *   POST /api/agent/trending/trigger   手动触发一次 GitHub Trending 流水线
 *   GET  /api/agent/sessions/{id}      查询指定会话的当前状态与进度
 *   GET  /api/agent/metrics            导出全部运行指标快照（仪表盘）
 *   GET  /api/agent/dlq                查看死信队列
 *   GET  /api/agent/queue/size         查看任务队列积压深度
 *   POST /api/agent/scheduler/pause    暂停消费（优雅停机演练）
 *   POST /api/agent/scheduler/resume   恢复消费
 * </pre>
 *
 * <p>设计原则：<b>Controller 极薄</b>——只做参数接收与结果组装，
 * 所有业务逻辑都委托给已装配好的领域组件（Runtime / Scheduler / Metrics ...），
 * 保持关注点分离，也让这些能力可被单元测试直接覆盖，不依赖 HTTP 层。</p>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentApiController {

    private final TrendingScheduler trendingScheduler;
    private final AgentRuntime runtime;
    private final AgentMetrics metrics;
    private final DeadLetterQueue deadLetterQueue;
    private final TaskQueue taskQueue;
    private final AgentScheduler scheduler;

    public AgentApiController(TrendingScheduler trendingScheduler,
                              AgentRuntime runtime,
                              AgentMetrics metrics,
                              DeadLetterQueue deadLetterQueue,
                              TaskQueue taskQueue,
                              AgentScheduler scheduler) {
        this.trendingScheduler = trendingScheduler;
        this.runtime = runtime;
        this.metrics = metrics;
        this.deadLetterQueue = deadLetterQueue;
        this.taskQueue = taskQueue;
        this.scheduler = scheduler;
    }

    /**
     * 手动触发一次 GitHub Trending 流水线（等价于每天 09:00 的定时触发）。
     *
     * <p>演示 / 联调最常用的入口：无需等到 9 点，一键开启一条完整会话。</p>
     *
     * @return 新创建的会话 ID
     */
    @PostMapping("/trending/trigger")
    public ResponseEntity<Map<String, String>> triggerTrending() {
        String sessionId = trendingScheduler.triggerOnce();
        return ResponseEntity.ok(Map.of(
                "message", "已触发一次 GitHub Trending 流水线",
                "sessionId", sessionId));
    }

    /**
     * 查询指定会话的当前状态与执行进度。
     *
     * @param id 会话 ID
     * @return 会话概览；不存在则 404
     */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String id) {
        return runtime.find(id)
                .map(this::toView)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 导出全部运行指标的只读快照——这是 Agent 的\"仪表盘\"。
     *
     * <p>生产环境可对接 Micrometer / Prometheus，把这些值暴露为
     * /actuator/prometheus 由 Grafana 绘图；此处提供最直白的 JSON 视图。</p>
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Long>> getMetrics() {
        return ResponseEntity.ok(metrics.snapshot());
    }

    /**
     * 查看死信队列——重试耗尽仍失败的\"毒丸任务\"都在这里。
     *
     * <p>运维铁律：DLQ size > 0 即应告警并人工介入。本端点便于快速排查。</p>
     */
    @GetMapping("/dlq")
    public ResponseEntity<Map<String, Object>> getDeadLetters() {
        List<Map<String, Object>> items = deadLetterQueue.findAll().stream()
                .map(this::toView)
                .toList();
        return ResponseEntity.ok(Map.of(
                "size", deadLetterQueue.size(),
                "items", items));
    }

    /** 查看任务队列当前积压深度（Gauge 指标之一）。 */
    @GetMapping("/queue/size")
    public ResponseEntity<Map<String, Object>> getQueueSize() {
        return ResponseEntity.ok(Map.of("queueSize", taskQueue.size()));
    }

    /** 暂停消费（优雅停机 / 故障隔离演练）。 */
    @PostMapping("/scheduler/pause")
    public ResponseEntity<Map<String, Object>> pause() {
        scheduler.pause();
        return ResponseEntity.ok(Map.of("running", scheduler.isRunning()));
    }

    /** 恢复消费。 */
    @PostMapping("/scheduler/resume")
    public ResponseEntity<Map<String, Object>> resume() {
        scheduler.resume();
        return ResponseEntity.ok(Map.of("running", scheduler.isRunning()));
    }

    // ---------------- 视图组装（DTO 化，避免直接暴露领域对象）----------------

    private Map<String, Object> toView(AgentSession s) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("sessionId", s.getSessionId());
        view.put("agentType", s.getAgentType());
        view.put("state", s.getState().name());
        view.put("stepIndex", s.getContext().getStepIndex());
        view.put("retryCount", s.getContext().getRetryCount());
        view.put("lastError", s.getLastError());
        view.put("createdAt", String.valueOf(s.getCreatedAt()));
        view.put("updatedAt", String.valueOf(s.getUpdatedAt()));
        return view;
    }

    private Map<String, Object> toView(DeadLetter dl) {
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("taskId", dl.task().getTaskId());
        view.put("sessionId", dl.task().getSessionId());
        view.put("taskType", dl.task().getType());
        view.put("reason", dl.reason());
        view.put("totalAttempts", dl.totalAttempts());
        view.put("deadAt", String.valueOf(dl.deadAt()));
        return view;
    }
}