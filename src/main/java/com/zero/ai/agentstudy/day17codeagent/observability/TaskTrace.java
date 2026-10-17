package com.zero.ai.agentstudy.day17codeagent.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务链路追踪器。
 *
 * <h3>为什么不用一个日志就完事</h3>
 * <p>{@code log.info("开始生成")} 这种日志有两个致命问题：</p>
 * <ol>
 *     <li><b>没有边界</b>：看不出这一步从哪开始到哪结束，也就算不出耗时；</li>
 *     <li><b>没有聚合</b>：看不到一次任务总共花了多少 token、多少时间。</li>
 * </ol>
 * <p>Trace 把「步骤」建模成<b>有起止的对象</b>，于是耗时与 token 可以被自动累加，
 * 最终能得到一张「瀑布图」——哪个环节是瓶颈一眼可见。</p>
 *
 * @author ZeroAi
 */
public class TaskTrace {

    private final String traceId;
    private final String taskId;
    private final long startedAt;
    private final List<AgentStep> steps = new ArrayList<>();
    private final AtomicInteger totalTokens = new AtomicInteger();
    private final AtomicLong totalElapsed = new AtomicLong();

    public TaskTrace(String traceId, String taskId) {
        this.traceId = traceId;
        this.taskId = taskId;
        this.startedAt = System.currentTimeMillis();
    }

    public static TaskTrace start(String traceId, String taskId) {
        return new TaskTrace(traceId, taskId);
    }

    /**
     * 记录一个已完成步骤。
     *
     * @param name      步骤名
     * @param status    状态
     * @param elapsedMs 耗时
     * @param tokens    token 数
     * @param detail    摘要
     */
    public AgentStep step(String name, AgentStep.Status status, long elapsedMs,
                          int tokens, String detail) {
        AgentStep step = new AgentStep(name, status, elapsedMs, tokens, detail);
        steps.add(step);
        totalTokens.addAndGet(tokens);
        totalElapsed.addAndGet(elapsedMs);
        return step;
    }

    /**
     * 用 lambda 包裹一段逻辑，自动计时并记步。
     *
     * <p>这是推荐用法：<b>计时不应该靠人记得写</b>。
     * 手写 {@code long t = now(); ... ; trace.step(..., now()-t)} 迟早会漏。</p>
     */
    public <T> T timed(String name, java.util.function.Supplier<T> body) {
        long t0 = System.currentTimeMillis();
        try {
            T value = body.get();
            step(name, AgentStep.Status.SUCCESS, System.currentTimeMillis() - t0, 0, null);
            return value;
        } catch (RuntimeException e) {
            step(name, AgentStep.Status.FAILED, System.currentTimeMillis() - t0, 0,
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    public void skipped(String name, String reason) {
        step(name, AgentStep.Status.SKIPPED, 0, 0, reason);
    }

    public List<AgentStep> steps() {
        return List.copyOf(steps);
    }

    public int totalTokens() {
        return totalTokens.get();
    }

    public long totalElapsedMs() {
        return totalElapsed.get();
    }

    public long wallClockMs() {
        return System.currentTimeMillis() - startedAt;
    }

    public String traceId() {
        return traceId;
    }

    public String taskId() {
        return taskId;
    }

    /** 耗时最长的一步（瓶颈）。 */
    public AgentStep bottleneck() {
        return steps.stream()
                .max(java.util.Comparator.comparingLong(AgentStep::elapsedMs))
                .orElse(null);
    }

    public boolean hasFailure() {
        return steps.stream().anyMatch(s -> s.status() == AgentStep.Status.FAILED);
    }

    /** 渲染成可读的时间线。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("Trace ").append(traceId).append(" / 任务 ").append(taskId).append('\n');
        for (AgentStep step : steps) {
            sb.append("  ").append(step).append('\n');
        }
        sb.append("  ------------------------------------------------\n");
        sb.append(String.format("  合计 %d 步, %d token, 步骤耗时 %dms（墙钟 %dms）%n",
                steps.size(), totalTokens.get(), totalElapsed.get(), wallClockMs()));
        AgentStep slowest = bottleneck();
        if (slowest != null) {
            sb.append("  瓶颈步骤：").append(slowest.name())
                    .append(" (").append(slowest.elapsedMs()).append("ms)");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "TaskTrace{" + traceId + ", 步骤 " + steps.size()
                + ", token " + totalTokens.get() + ", " + totalElapsed.get() + "ms}";
    }
}
