package com.zero.ai.agentstudy.day10planningagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 计划聚合根：一组有依赖关系的步骤。
 */
public class Plan {

    private final String id;
    private final List<PlanStep> steps;

    public Plan(List<PlanStep> steps) {
        this.id = "plan-" + UUID.randomUUID().toString().substring(0, 8);
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
    }

    public String id() { return id; }

    public List<PlanStep> steps() { return steps; }

    /** 按 id 查找步骤。 */
    public Optional<PlanStep> findById(String stepId) {
        return steps.stream().filter(s -> s.id().equals(stepId)).findFirst();
    }

    /** 所有步骤是否都已结算（DONE 或 SKIPPED）。 */
    public boolean allSettled() {
        return steps.stream().allMatch(PlanStep::isSettled);
    }

    /** 是否存在失败步骤。 */
    public boolean hasFailed() {
        return steps.stream().anyMatch(PlanStep::isFailed);
    }

    /** 添加步骤（供重规划增量合并使用）。 */
    public void addStep(PlanStep step) {
        this.steps.add(step);
    }

    /** 漂亮打印，用于日志与结果展示。 */
    public String prettyPrint() {
        StringBuilder sb = new StringBuilder();
        sb.append("计划[").append(id).append("] 共 ").append(steps.size()).append(" 步\n");
        for (PlanStep s : steps) {
            String mark = switch (s.status()) {
                case DONE -> "✓";
                case FAILED -> "✗";
                case RUNNING -> "▶";
                case SKIPPED -> "⊘";
                case PENDING -> "○";
            };
            sb.append("  ").append(mark).append(" ")
              .append(s.id()).append(" ").append(s.action())
              .append(" [").append(s.status()).append("]\n");
        }
        return sb.toString();
    }
}