package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 链路采集器（Chapter 14）。
 *
 * <p>用 {@link #measure} 包裹每个阶段的执行，自动记录耗时与成败，生成 {@link Span}。
 * 一次问答结束后调用 {@link #report} 就能看到完整的分阶段耗时账单——这正是排查
 * 「P99 延迟高」时最需要的第一手数据。</p>
 *
 * <p>本实现非线程安全，按「一次请求一个采集器」使用；跨请求聚合交给 Micrometer。</p>
 *
 * @author ZeroAi
 */
public class TraceCollector {

    private final String traceId;
    private final List<Span> spans = new ArrayList<>();

    public TraceCollector(AgentContext ctx) {
        this.traceId = ctx.traceId();
    }

    /**
     * 计时执行一个有返回值的阶段。异常也会被记录为失败 Span 后重新抛出。
     *
     * @param stage 阶段名
     * @param task  阶段逻辑
     * @return 阶段返回值
     */
    public <T> T measure(String stage, Supplier<T> task) {
        long start = System.nanoTime();
        boolean ok = false;
        try {
            T result = task.get();
            ok = true;
            return result;
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            spans.add(new Span(traceId, stage, costMs, ok));
        }
    }

    /** 计时执行一个无返回值的阶段。 */
    public void measure(String stage, Runnable task) {
        measure(stage, () -> {
            task.run();
            return null;
        });
    }

    public List<Span> spans() {
        return List.copyOf(spans);
    }

    public long totalMs() {
        return spans.stream().mapToLong(Span::costMs).sum();
    }

    /** 打印分阶段耗时账单。 */
    public String report() {
        StringBuilder sb = new StringBuilder("链路 ").append(traceId)
                .append("（总计 ").append(totalMs()).append("ms）:\n");
        spans.forEach(s -> sb.append("  ").append(s).append('\n'));
        return sb.toString();
    }
}