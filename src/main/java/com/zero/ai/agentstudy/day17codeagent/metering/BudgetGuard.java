package com.zero.ai.agentstudy.day17codeagent.metering;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 预算护栏：在每次「花钱的动作」之前检查余额。
 *
 * <h3>为什么必须在动作之前检查，而不是事后统计</h3>
 * <p>事后统计只能告诉你「这个月超支了 3000 块」。
 * 事前检查才能阻止超支发生。</p>
 *
 * <p><b>关键实现细节</b>：检查与扣减必须<b>原子</b>，
 * 否则并发任务会一起通过检查（典型的 check-then-act 竞态）。
 * 这里用 synchronized 保证。</p>
 *
 * @author ZeroAi
 */
public class BudgetGuard {

    private final TaskBudget budget;
    private int usedTokens;
    private int modelCalls;
    private int repairRounds;
    private final long startedAt;
    private final List<String> rejections = new ArrayList<>();

    public BudgetGuard(TaskBudget budget) {
        this.budget = budget;
        this.startedAt = System.currentTimeMillis();
    }

    public static BudgetGuard of(TaskBudget budget) {
        return new BudgetGuard(budget);
    }

    /**
     * 申请一次模型调用。
     *
     * @param estimatedTokens 预估将消耗的 token
     * @throws CodeException 超预算时抛出 BUDGET_EXCEEDED / QUOTA_EXCEEDED
     */
    public synchronized void acquireModelCall(int estimatedTokens) {
        if (modelCalls + 1 > budget.maxModelCalls()) {
            reject("调用次数达到上限 " + budget.maxModelCalls() + "（QUOTA_EXCEEDED）");
        }
        if (usedTokens + estimatedTokens > budget.maxTokens()) {
            reject("token 预算不足：已用 " + usedTokens + "，本次需要 " + estimatedTokens
                    + "，上限 " + budget.maxTokens() + "（BUDGET_EXCEEDED）");
        }
        checkWallClock();
        modelCalls++;
    }

    /** 结算一次模型调用的实际消耗。 */
    public synchronized void settleTokens(int actualTokens) {
        usedTokens += actualTokens;
    }

    /** 申请一轮自修复。 */
    public synchronized void acquireRepairRound() {
        if (repairRounds + 1 > budget.maxRepairRounds()) {
            reject("自修复轮次达到上限 " + budget.maxRepairRounds() + "（QUOTA_EXCEEDED）");
        }
        checkWallClock();
        repairRounds++;
    }

    /** 墙钟超时检查。 */
    public synchronized void checkWallClock() {
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed > budget.maxWallClockMs()) {
            reject("任务运行时间 " + elapsed + "ms 超过上限 " + budget.maxWallClockMs()
                    + "ms（BUDGET_EXCEEDED）");
        }
    }

    private void reject(String reason) {
        rejections.add(reason);
        throw new CodeException(CodeErrorCode.BUDGET_EXCEEDED, reason);
    }

    public synchronized int usedTokens() {
        return usedTokens;
    }

    public synchronized int modelCalls() {
        return modelCalls;
    }

    public synchronized int repairRounds() {
        return repairRounds;
    }

    public synchronized List<String> rejections() {
        return List.copyOf(rejections);
    }

    public synchronized long elapsedMs() {
        return System.currentTimeMillis() - startedAt;
    }

    /** 预算使用率 [0.0,1.0]。 */
    public synchronized double usage() {
        return Math.min(1.0, (double) usedTokens / budget.maxTokens());
    }

    /** 已消耗金额的估算。 */
    public synchronized double estimatedCost() {
        return budget.estimateCost(usedTokens);
    }

    public TaskBudget budget() {
        return budget;
    }

    @Override
    public String toString() {
        return String.format("BudgetGuard{token %d/%d (%.0f%%), 调用 %d/%d, 修复 %d/%d, "
                        + "预计花费 ¥%.2f, %dms}",
                usedTokens, budget.maxTokens(), usage() * 100,
                modelCalls, budget.maxModelCalls(),
                repairRounds, budget.maxRepairRounds(),
                estimatedCost(), elapsedMs());
    }
}
