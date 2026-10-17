package com.zero.ai.agentstudy.day17codeagent.eval;

import com.zero.ai.agentstudy.day17codeagent.generator.StubCodeModel;

import java.util.List;

/**
 * Chapter 18 演示：Code Agent 评测。
 *
 * <p>跑真实任务、真实编译、真实测试，产出可比较的指标。</p>
 *
 * @author ZeroAi
 */
public class Chapter18Demo {

    public static void main(String[] args) {
        System.out.println("=== [1] 评测单个任务 ===");
        CodeEvaluator evaluator = new CodeEvaluator(StubCodeModel.reliable());
        TaskOutcome one = evaluator.runOnce("T1",
                "请给 Money 增加 multiply 方法，支持乘以一个整数倍率", null, 3);
        System.out.println("  " + one);
        System.out.println("  补丁应用率 = " + String.format("%.0f%%", one.patchApplyRate() * 100)
                + "  succeeded = " + one.succeeded());

        // ---- 2. 一批任务 ----
        System.out.println("\n=== [2] 一批任务的聚合指标 ===");
        List<String[]> tasks = List.of(
                new String[]{"T-multiply", "给 Money 增加 multiply 方法"},
                new String[]{"T-pay", "给 OrderService 增加 pay 方法"},
                new String[]{"T-discount", "简化 DiscountPolicy 的 VIP_THRESHOLD 判断"},
                new String[]{"T-unknown", "给这个类加上量子加密传输能力"}
        );
        EvalReport report = evaluator.runSuite(tasks, null, 3);
        report.outcomes().forEach(o -> System.out.println("    " + o));
        System.out.println("\n  " + report);
        System.out.println("  失败归因：");
        report.failureBreakdown()
                .forEach((reason, count) -> System.out.println("    " + count + " × " + reason));

        System.out.println("\n  → 注意最后一条：模型对「量子加密传输」这种无从下手的需求，");
        System.out.println("    返回了无法解析的回答。这比「硬编一段编译不过的代码」有价值得多。");

        // ---- 3. Pass@k：多次采样 ----
        System.out.println("\n=== [3] Pass@k：同一个任务跑多次 ===");
        CodeEvaluator flakyEvaluator = new CodeEvaluator(StubCodeModel.flaky());
        String requirement = "请给 Money 增加 multiply 方法，支持乘以一个整数倍率";
        java.util.List<TaskOutcome> samples = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            TaskOutcome o = flakyEvaluator.runOnce("S" + i, requirement, null, 2);
            samples.add(o);
            System.out.println("    采样 " + i + "：" + (o.succeeded() ? "成功" : "失败")
                    + "（" + o.repairRounds() + " 轮修复）");
        }
        EvalReport sameTask = new EvalReport(samples);
        System.out.println("  n = " + sameTask.size()
                + ", c = " + sameTask.succeededCount());
        System.out.println("  Pass@1 = " + String.format("%.1f%%", sameTask.passAt1() * 100));
        System.out.println("  Pass@2 = " + String.format("%.1f%%", sameTask.passAtK(2) * 100));
        System.out.println("  Pass@3 = " + String.format("%.1f%%", sameTask.passAtK(3) * 100));
        System.out.println("  → Pass@1 才是用户实际感受到的成功率；");
        System.out.println("    Pass@k 与 Pass@1 差距越大，说明自修复循环越有价值。");

        // ---- 4. 组合数计算正确性自检 ----
        System.out.println("\n=== [4] Pass@k 公式自检 ===");
        System.out.println("  C(5,3) = " + EvalReport.combinations(5, 3) + "（应为 10）");
        System.out.println("  C(5,5) = " + EvalReport.combinations(5, 5) + "（应为 1）");
        System.out.println("  C(3,5) = " + EvalReport.combinations(3, 5) + "（越界应为 0）");

        // ---- 5. 指标解读 ----
        System.out.println("\n=== [5] 四个指标分别下降时该查哪里 ===");
        System.out.println("  补丁应用率低  → 上下文选错了文件 / 补丁里的代码片段不是当前文件内容");
        System.out.println("  编译通过率低  → 模型能力或提示词问题 / 自修复轮次不够");
        System.out.println("  测试通过率低  → 语义理解有误，光靠语法验证救不了");
        System.out.println("  token 消耗高  → 上下文塞太多，或自修复循环失控（检查无进展检测）");
    }
}
