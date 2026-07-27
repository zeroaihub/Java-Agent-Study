package com.zero.ai.agentstudy.day10planningagent.context;

import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 规划上下文（黑板模式）。
 * 所有组件读写同一块共享黑板：目标、计划、状态、观察、计数器、轨迹。
 * 采用线程安全容器，支持 chapter-08 的并行执行。
 */
public class PlanningContext {

    private final Goal goal;
    private volatile Plan plan;
    private volatile PlanState state = PlanState.NEW;

    private final List<Observation> observations = new CopyOnWriteArrayList<>();
    private final Map<String, Object> blackboard = new ConcurrentHashMap<>();
    private final List<TraceEvent> trace = new CopyOnWriteArrayList<>();

    private final AtomicInteger stepCount = new AtomicInteger(0);
    private final AtomicInteger replanCount = new AtomicInteger(0);
    private final long startedAt = System.currentTimeMillis();

    public PlanningContext(Goal goal) {
        this.goal = goal;
    }

    // ---------- 状态机 ----------

    public synchronized void transitionTo(PlanState target) {
        if (state == target) return;
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateException("非法状态转移: " + state + " -> " + target);
        }
        trace(TraceEvent.of("STATE", state + " -> " + target));
        this.state = target;
    }

    public PlanState state() { return state; }

    // ---------- 观察记录 ----------

    public void record(Observation obs) {
        observations.add(obs);
        // 成功输出也写入黑板，供后续步骤按 stepId 读取
        if (obs.success() && obs.output() != null) {
            blackboard.put(obs.stepId(), obs.output());
        }
    }

    public List<Observation> observations() { return observations; }

    public Observation lastObservation() {
        return observations.isEmpty() ? null : observations.get(observations.size() - 1);
    }

    // ---------- 黑板读写 ----------

    public void put(String key, Object value) { blackboard.put(key, value); }
    public Object get(String key) { return blackboard.get(key); }

    /** 汇总所有已完成步骤的输出，作为后续步骤/反思的上下文。 */
    public String completedSummary() {
        if (plan == null) return "";
        StringBuilder sb = new StringBuilder();
        for (PlanStep s : plan.steps()) {
            if (s.isDone()) {
                sb.append("[").append(s.id()).append("] ").append(s.action())
                  .append(" => ")
                  .append(truncate(s.output(), 500))
                  .append("\n");
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "...(截断)" : s;
    }

    // ---------- 预算护栏 ----------

    public int incrementStep() { return stepCount.incrementAndGet(); }
    public int incrementReplan() { return replanCount.incrementAndGet(); }
    public int stepCount() { return stepCount.get(); }
    public int replanCount() { return replanCount.get(); }

    /** 每轮循环前校验预算，超限则抛异常（防死循环）。 */
    public void guardBudgetOrThrow() {
        if (stepCount.get() >= goal.maxSteps()) {
            throw new BudgetExceededException("超出最大步数: " + goal.maxSteps());
        }
        if (replanCount.get() > goal.maxReplan()) {
            throw new BudgetExceededException("超出最大重规划次数: " + goal.maxReplan());
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > goal.timeoutMs()) {
            throw new BudgetExceededException("任务超时: " + elapsed + "ms > " + goal.timeoutMs() + "ms");
        }
    }

    // ---------- 轨迹 ----------

    public void trace(TraceEvent event) { trace.add(event); }
    public List<TraceEvent> traces() { return trace; }

    // ---------- getters ----------

    public Goal goal() { return goal; }
    public Plan plan() { return plan; }
    public void setPlan(Plan plan) { this.plan = plan; }
}