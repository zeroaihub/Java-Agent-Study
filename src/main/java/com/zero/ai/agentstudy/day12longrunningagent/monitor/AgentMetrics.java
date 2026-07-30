package com.zero.ai.agentstudy.day12longrunningagent.monitor;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 运行指标收集器。
 *
 * <p>一台永不停歇的机器必须有仪表盘。本类是最小可用的进程内指标中心，
 * 收集长生命周期 Agent 运行中最关键的几类计数与量表：
 * <ul>
 *   <li><b>Counter（计数器）</b>：只增不减，如"累计完成任务数""累计失败数""进死信数"。</li>
 *   <li><b>Gauge（量表）</b>：可增可减的瞬时值，如"当前运行中的会话数""队列积压深度"。</li>
 * </ul>
 *
 * <p>所有指标用 {@link AtomicLong} 保证并发下的原子累加，用
 * {@link ConcurrentHashMap} 承载动态命名的指标——生产环境可无缝对接
 * Micrometer / Prometheus，把这些数值暴露为 /actuator/metrics。</p>
 */
@Component
public class AgentMetrics {

    /** 计数器集合：name → 累计值。 */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 量表集合：name → 当前值。 */
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    // ---- 预定义的关键指标名（避免魔法字符串散落各处）----
    public static final String TASK_SUCCESS = "task.success";
    public static final String TASK_FAILED = "task.failed";
    public static final String TASK_RETRIED = "task.retried";
    public static final String TASK_DEAD = "task.dead";
    public static final String SESSION_STARTED = "session.started";
    public static final String SESSION_COMPLETED = "session.completed";
    public static final String CHECKPOINT_SAVED = "checkpoint.saved";
    public static final String RECOVERY_TRIGGERED = "recovery.triggered";
    public static final String EVENT_PUBLISHED = "event.published";

    public static final String GAUGE_RUNNING_SESSIONS = "gauge.running.sessions";
    public static final String GAUGE_QUEUE_DEPTH = "gauge.queue.depth";

    // ---------------- Counter ----------------

    /** 计数器 +1。 */
    public void increment(String name) {
        incrementBy(name, 1L);
    }

    /** 计数器 +delta。 */
    public void incrementBy(String name, long delta) {
        counters.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    /** 读取计数器当前值（不存在返回 0）。 */
    public long counter(String name) {
        AtomicLong c = counters.get(name);
        return c == null ? 0L : c.get();
    }

    // ---------------- Gauge ----------------

    /** 设置量表瞬时值。 */
    public void setGauge(String name, long value) {
        gauges.computeIfAbsent(name, k -> new AtomicLong()).set(value);
   }

    /** 量表 +delta（delta 可为负）。 */
    public void addGauge(String name, long delta) {
        gauges.computeIfAbsent(name, k -> new AtomicLong()).addAndGet(delta);
    }

    /** 读取量表当前值（不存在返回 0）。 */
    public long gauge(String name) {
        AtomicLong g = gauges.get(name);
        return g == null ? 0L : g.get();
    }

    // ---------------- 快照 ----------------

    /**
     * 导出全部指标的只读快照，供监控端点/日志打印。
     *
     * @return 指标名 → 当前值 的不可变映射
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> snap = new java.util.LinkedHashMap<>();
        counters.forEach((k, v) -> snap.put(k, v.get()));
        gauges.forEach((k, v) -> snap.put(k, v.get()));
        return Map.copyOf(snap);
    }

    /** 测试/重置用：清空所有指标。 */
    public void reset() {
        counters.clear();
        gauges.clear();
    }
}