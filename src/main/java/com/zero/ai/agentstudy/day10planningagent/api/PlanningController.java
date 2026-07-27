package com.zero.ai.agentstudy.day10planningagent.api;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.Goal;
import com.zero.ai.agentstudy.day10planningagent.service.PlanningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day10 Planning Agent 对外 HTTP 入口。
 *
 * <p>接口：
 * <ul>
 *   <li>POST /api/day10/planning/run —— 完整入参运行</li>
 *   <li>GET  /api/day10/planning/demo —— 一键跑 GitHub Trending 端到端 Demo</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/day10/planning")
public class PlanningController {

    /** GitHub Trending 端到端 Demo 的默认目标。 */
    private static final String DEMO_GOAL =
            "分析 GitHub Trending 上最热门的 AI Agent 相关项目，" +
            "提取其名称、简介、语言与 star 数，并生成一份结构化的 Markdown 分析报告。";

    private final PlanningService planningService;

    public PlanningController(PlanningService planningService) {
        this.planningService = planningService;
    }

    @PostMapping("/run")
    public ResponseEntity<RunResponse> run(@RequestBody RunRequest req) {
        Goal goal = Goal.of(req.goal(), req.maxSteps(), req.maxReplan(), req.timeoutMs());
        PlanningContext ctx = planningService.run(goal);
        return ResponseEntity.ok(RunResponse.from(ctx));
    }

    @GetMapping("/demo")
    public ResponseEntity<RunResponse> demo(
            @RequestParam(value = "goal", required = false) String goal) {
        String finalGoal = (goal == null || goal.isBlank()) ? DEMO_GOAL : goal;
        Goal g = Goal.of(finalGoal, 8, 3, 120_000L);
        PlanningContext ctx = planningService.run(g);
        return ResponseEntity.ok(RunResponse.from(ctx));
    }
}