package com.zero.ai.agentstudy.day10planningagent.engine;

import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 DAG 的调度器。
 * - 就绪判定：一个 PENDING 步骤的所有依赖都已 DONE，则就绪。
 * - 优先级排序：就绪步骤中选 priority.weight 最大者。
 * - 环检测：DFS 三色标记法。
 */
@Component
public class DagScheduler implements Scheduler {

    @Override
    public Optional<PlanStep> nextStep(Plan plan) {
        return readySteps(plan).stream()
                .max(Comparator.comparingInt(s -> s.priority().weight()));
    }

    @Override
    public List<PlanStep> readySteps(Plan plan) {
        List<PlanStep> ready = new ArrayList<>();
        for (PlanStep s : plan.steps()) {
            if (s.isPending() && dependenciesSatisfied(plan, s)) {
                ready.add(s);
            }
        }
        return ready;
    }

    /** 依赖是否全部完成（DONE）。 */
    private boolean dependenciesSatisfied(Plan plan, PlanStep step) {
        for (String depId : step.deps()) {
            Optional<PlanStep> dep = plan.findById(depId);
            if (dep.isEmpty() || !dep.get().isDone()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isAllDone(Plan plan) {
        return plan.allSettled();
    }

    // ---------- DFS 三色标记法环检测 ----------
    // 0=白（未访问），1=灰（访问中/在栈上），2=黑（已完成）

    @Override
    public void validateNoCycle(Plan plan) {
        Map<String, Integer> color = new HashMap<>();
        Map<String, PlanStep> byId = new HashMap<>();
        for (PlanStep s : plan.steps()) {
            byId.put(s.id(), s);
            color.put(s.id(), 0);
        }
        for (PlanStep s : plan.steps()) {
            if (color.get(s.id()) == 0) {
                dfs(s.id(), byId, color);
            }
        }
    }

    private void dfs(String id, Map<String, PlanStep> byId, Map<String, Integer> color) {
        color.put(id, 1); // 标灰
        PlanStep step = byId.get(id);
        if (step != null) {
            for (String depId : step.deps()) {
                Integer c = color.get(depId);
                if (c == null) {
                    // 依赖了不存在的步骤，视为非法计划
                    throw new IllegalStateException("步骤 " + id + " 依赖了不存在的步骤: " + depId);
                }
                if (c == 1) {
                    throw new IllegalStateException("检测到依赖环，涉及步骤: " + id + " -> " + depId);
                }
                if (c == 0) {
                    dfs(depId, byId, color);
                }
            }
        }
        color.put(id, 2); // 标黑
    }
}