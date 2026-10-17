package com.zero.ai.agentstudy.day17codeagent.eval;

/**
 * 一次 Code Agent 任务的评测记录。
 *
 * <p>为什么要把这些字段全部留下来：Code Agent 的优化对象是<b>整个链路的质量</b>，
 * 而「Agent 好不好」从来不是单一指标能回答的。至少要看：</p>
 * <ul>
 *     <li>补丁能不能打上（Patch Apply Rate）—— 低了说明<b>选址/上下文</b>有问题；</li>
 *     <li>编译能不能过（Compile Pass Rate）—— 低了说明<b>代码质量</b>有问题；</li>
 *     <li>测试能不能过（Test Pass Rate）—— 低了说明<b>语义理解</b>有问题；</li>
 *     <li>花了多少 token / 多少轮 —— 决定<b>成本能不能接受</b>。</li>
 * </ul>
 *
 * <p>四个指标下降的含义完全不同，混在一起看就永远找不到优化方向。</p>
 *
 * @param taskId          任务 ID
 * @param modelId         使用的模型
 * @param patchesTotal    模型给出的补丁数
 * @param patchesApplied  成功应用的补丁数
 * @param compiled        是否编译通过
 * @param testPassed      测试是否通过
 * @param repairRounds    自修复轮次
 * @param tokens          总 token 消耗
 * @param elapsedMs       总耗时
 * @param failureReason   失败原因（成功时为 null）
 * @author ZeroAi
 */
public record TaskOutcome(
        String taskId,
        String modelId,
        int patchesTotal,
        int patchesApplied,
        boolean compiled,
        boolean testPassed,
        int repairRounds,
        int tokens,
        long elapsedMs,
        String failureReason
) {

    public boolean succeeded() {
        return compiled && testPassed && patchesApplied == patchesTotal;
    }

    /** 补丁应用率 [0.0,1.0]。 */
    public double patchApplyRate() {
        if (patchesTotal == 0) {
            return 0.0;
        }
        return (double) patchesApplied / patchesTotal;
    }

    @Override
    public String toString() {
        return String.format("%s %s | 补丁 %d/%d | 编译 %s | 测试 %s | 修复 %d 轮 | %d token | %dms%s",
                taskId, modelId, patchesApplied, patchesTotal,
                compiled ? "通过" : "失败", testPassed ? "通过" : "失败",
                repairRounds, tokens, elapsedMs,
                failureReason == null ? "" : " | " + failureReason);
    }
}
