package com.zero.ai.agentstudy.day10planningagent.core.planner;

import com.zero.ai.agentstudy.day10planningagent.core.Plan;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import com.zero.ai.agentstudy.day10planningagent.core.Priority;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 结构化输出的防腐层 DTO。
 * 与 LLM JSON 一一对应，随后转换为领域对象 Plan，隔离外部格式对领域的侵入。
 */
public class PlanDto {

    public List<StepDto> steps = new ArrayList<>();

    public static class StepDto {
        public String id;
        public String action;
        public String tool;        // 建议工具，可为空
        public String priority;    // LOW/MEDIUM/HIGH
        public List<String> deps = new ArrayList<>();
    }

    /** 转为领域对象。 */
    public Plan toDomain() {
        List<PlanStep> planSteps = new ArrayList<>();
        for (StepDto d : steps) {
            Priority p;
            try {
            p = d.priority == null ? Priority.MEDIUM : Priority.valueOf(d.priority.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                p = Priority.MEDIUM;
            }
            planSteps.add(new PlanStep(d.id, d.action, d.tool, p, d.deps));
        }
        return new Plan(planSteps);
    }
}