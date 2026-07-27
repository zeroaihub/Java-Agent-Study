package com.zero.ai.agentstudy.day10planningagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划中的一个步骤（充血领域对象）。
 * 封装自身状态流转，禁止外部随意 setStatus，保证状态一致性。
 */
public class PlanStep {

    private final String id;
    private final String action;          // 步骤要做的事（自然语言）
    private final String suggestedTool;   // 规划器建议的工具名（可为空，由 ToolSelector 兜底）
    private final Priority priority;
    private final List<String> deps;      // 依赖的前置步骤 id
    private StepStatus status;
    private String output;                // 执行输出
    private String error;                 // 失败原因
    private int attemptCount;             // 已尝试次数

    public PlanStep(String id, String action, String suggestedTool,
                    Priority priority, List<String> deps) {
        this.id = id;
        this.action = action;
        this.suggestedTool = suggestedTool;
        this.priority = priority == null ? Priority.MEDIUM : priority;
        this.deps = deps == null ? new ArrayList<>() : new ArrayList<>(deps);
        this.status = StepStatus.PENDING;
        this.attemptCount = 0;
    }

    // ---------- 状态流转（充血行为）----------

    public void markRunning() {
        this.status = StepStatus.RUNNING;
        this.attemptCount++;
    }

    public void markDone(String output) {
        this.status = StepStatus.DONE;
        this.output = output;
        this.error = null;
    }

    public void markFailed(String error) {
        this.status = StepStatus.FAILED;
        this.error = error;
    }

    public void markSkipped() {
        this.status = StepStatus.SKIPPED;
    }

    /** 为重试重置为 PENDING（保留 attemptCount 计数）。 */
    public void retryWith() {
        this.status = StepStatus.PENDING;
        this.error = null;
    }

    // ---------- 查询 ----------

    public boolean isDone() { return status == StepStatus.DONE; }
    public boolean isFailed() { return status == StepStatus.FAILED; }
    public boolean isPending() { return status == StepStatus.PENDING; }
    public boolean isSettled() {
        return status == StepStatus.DONE || status == StepStatus.SKIPPED;
    }

    // ---------- getters ----------

    public String id() { return id; }
    public String action() { return action; }
    public String suggestedTool() { return suggestedTool; }
    public Priority priority() { return priority; }
    public List<String> deps() { return deps; }
    public StepStatus status() { return status; }
    public String output() { return output; }
    public String error() { return error; }
    public int attemptCount() { return attemptCount; }

    @Override
    public String toString() {
        return "%s [%s] %s (tool=%s, prio=%s, deps=%s, attempts=%d)"
                .formatted(id, status, action, suggestedTool, priority, deps, attemptCount);
    }
}