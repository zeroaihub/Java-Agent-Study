package com.zero.ai.agentstudy.day17codeagent.eval;

import java.util.List;

/**
 * 评测报告：把多次任务记录聚合为可比较的指标。
 *
 * <h3>Pass@k 是什么，为什么必须用它</h3>
 * <p>问「这个 Agent 能不能做出来」，单次结果是没法回答的——同一个任务跑三次，
 * 可能一次成功两次失败，因为 LLM 本身有随机性。</p>
 *
 * <p>Pass@k 的定义：<b>给 k 次机会，至少有一次成功</b>的概率。
 * 它才是「Agent 能力上限」的正确度量，而 Pass@1 才是「用户实际体验到的成功率」。
 * 两者差值越大，说明结果越不稳定，越应该靠自修复循环与验证去收敛。</p>
 *
 * <p>这里用 HumanEval 论文里的<b>无偏估计量</b>：
 * 设 n 次采样中成功了 c 次，则</p>
 * <pre>
 *   pass@k = 1 - C(n-c, k) / C(n, k)
 * </pre>
 * <p>直接拿「成功率」当 pass@k 会低估：<b>n=5, c=3</b> 时，朴素算法说 60%，
 * 无偏估计给的是 (1 + 3/5 + 2/5 + 1/5)/… 量级的更高值。Demo 里会打印两者的差。</p>
 *
 * @param outcomes 全部任务记录
 * @author ZeroAi
 */
public record EvalReport(List<TaskOutcome> outcomes) {

    public int size() {
        return outcomes.size();
    }

    public long succeededCount() {
        return outcomes.stream().filter(TaskOutcome::succeeded).count();
    }

    /** Pass@1：用户实际感受到的成功率（朴素成功率）。 */
    public double passAt1() {
        if (outcomes.isEmpty()) {
            return 0.0;
        }
        return (double) succeededCount() / outcomes.size();
    }

    /**
     * 无偏 Pass@k（要求同一批数据是同一个任务的多次采样）。
     */
    public double passAtK(int k) {
        int n = outcomes.size();
        int c = (int) succeededCount();
        if (n == 0 || k <= 0) {
            return 0.0;
        }
        if (k > n) {
            k = n;
        }
        if (n - c < k) {
            return 1.0;
        }
        return 1.0 - (double) combinations(n - c, k) / combinations(n, k);
    }

    public double compilePassRate() {
        return ratio(outcomes.stream().filter(TaskOutcome::compiled).count());
    }

    public double testPassRate() {
        return ratio(outcomes.stream().filter(TaskOutcome::testPassed).count());
    }

    public double avgPatchApplyRate() {
        if (outcomes.isEmpty()) {
            return 0.0;
        }
        return outcomes.stream()
                .mapToDouble(TaskOutcome::patchApplyRate).average().orElse(0.0);
    }

    public double avgTokens() {
        return outcomes.isEmpty() ? 0.0
                : outcomes.stream().mapToInt(TaskOutcome::tokens).average().orElse(0.0);
    }

    public double avgElapsedMs() {
        return outcomes.isEmpty() ? 0.0
                : outcomes.stream().mapToLong(TaskOutcome::elapsedMs).average().orElse(0.0);
    }

    public double avgRepairRounds() {
        return outcomes.isEmpty() ? 0.0
                : outcomes.stream().mapToInt(TaskOutcome::repairRounds).average().orElse(0.0);
    }

    /**
     * 失败原因归因分布。
     *
     * <p>这一步最有价值：它直接告诉你「下一次该优化哪个模块」。</p>
     */
    public java.util.Map<String, Long> failureBreakdown() {
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        outcomes.stream()
                .filter(o -> !o.succeeded())
                .forEach(o -> {
                    String key = o.failureReason() == null ? "(未说明)" : o.failureReason();
                    counts.merge(key, 1L, Long::sum);
                });
        return java.util.Map.copyOf(counts);
    }

    private double ratio(long count) {
        return outcomes.isEmpty() ? 0.0 : (double) count / outcomes.size();
    }

    static long combinations(int n, int k) {
        if (k > n) {
            return 0;
        }
        long result = 1L;
        for (int i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format(
                "EvalReport{样本 %d, Pass@1=%.1f%%, Pass@3=%.1f%%, 补丁应用率=%.1f%%, "
                        + "编译通过率=%.1f%%, 测试通过率=%.1f%%, 平均 %.0f token, 平均 %d 轮}",
                size(), passAt1() * 100, passAtK(3) * 100, avgPatchApplyRate() * 100,
                compilePassRate() * 100, testPassRate() * 100, avgTokens(),
                Math.round(avgRepairRounds()));
    }
}
