package com.zero.ai.agentstudy.day16knowledgeagentv2.eval;

import java.util.List;
import java.util.Map;

/**
 * Chapter 13 端到端演示：离线评估作为上线前的「守门员」。
 *
 * <p>新版本的年假题检索漏了、答案也写错，命中率与相关性双双下滑——在上线前
 * 就被评估拦下，而不是等真实用户点踩。这就是离线评估的价值。</p>
 *
 * @author ZeroAi
 */
public class Chapter13Demo {

    public static void main(String[] args) {
        // 1. 构建评估集
        var dataset = List.of(
                new EvalSample("全职员工年假多少天？", "15 天", List.of("c-hr-007")),
                new EvalSample("试用期多久？", "6 个月", List.of("c-hr-012")));

        var evaluator = new Evaluator(List.of(
                new HitRateMetric(), new AnswerRelevancyMetric()));

        // 2. 基线版本：检索命中、答案正确
        Map<String, Double> base = evaluator.evaluate(dataset, s ->
                switch (s.query()) {
                    case "全职员工年假多少天？" -> new SystemOutput(List.of("c-hr-007"), "15 天");
                    default -> new SystemOutput(List.of("c-hr-012"), "6 个月");
                });
        System.out.println("[基线版] " + base);

        // 3. 新版本：年假题检索漏了、答案还写错成 20 天
        Map<String, Double> candidate = evaluator.evaluate(dataset, s ->
                switch (s.query()) {
                    case "全职员工年假多少天？" -> new SystemOutput(List.of("c-hr-999"), "20 天");
                    default -> new SystemOutput(List.of("c-hr-012"), "6 个月");
                });
        System.out.println("[新版本] " + candidate);

        // 4. 对比裁决
        boolean better = candidate.get("HitRate") >= base.get("HitRate")
                && candidate.get("AnswerRelevancy") >= base.get("AnswerRelevancy");
        System.out.println(better ? "✅ 新版本不劣于基线，允许上线" : "❌ 新版本出现回退，打回优化");
    }
}