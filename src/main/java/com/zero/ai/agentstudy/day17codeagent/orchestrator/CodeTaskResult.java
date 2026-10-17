package com.zero.ai.agentstudy.day17codeagent.orchestrator;

import com.zero.ai.agentstudy.day17codeagent.eval.TaskOutcome;
import com.zero.ai.agentstudy.day17codeagent.gitops.ChangeBundle;
import com.zero.ai.agentstudy.day17codeagent.gitops.PullRequestDraft;
import com.zero.ai.agentstudy.day17codeagent.observability.TaskTrace;
import com.zero.ai.agentstudy.day17codeagent.review.ReviewComment;
import com.zero.ai.agentstudy.day17codeagent.verify.CompileVerifier;

import java.util.List;

/**
 * 一次 Code Agent 任务的完整产出。
 *
 * <p>它回答了人类评审者关心的一切：<b>改了什么、验证到什么程度、还剩什么风险</b>。
 * 只返回「成功 / 失败」的 Agent 是无法被信任的。</p>
 *
 * @param taskId       任务 ID
 * @param traceId      追踪 ID
 * @param status       最终状态
 * @param patchesApplied 应用的补丁数
 * @param bundle       变更集
 * @param compileResult 最终编译结果
 * @param testSummary  测试摘要（可为 null）
 * @param reviewComments 评审意见
 * @param prDraft      PR 草稿（可为 null）
 * @param trace        链路追踪
 * @param repairRounds 自修复实际执行轮次
 * @param message      补充说明
 * @author ZeroAi
 */
public record CodeTaskResult(
        String taskId,
        String traceId,
        Status status,
        int patchesApplied,
        ChangeBundle bundle,
        CompileVerifier.CompilationResult compileResult,
        String testSummary,
        List<ReviewComment> reviewComments,
        PullRequestDraft prDraft,
        TaskTrace trace,
        int repairRounds,
        String message
) {

    public enum Status {
        /** 编译 + 测试全通过。 */
        SUCCEEDED("成功"),
        /** 编译通过但测试未通过。 */
        TEST_FAILED("测试未通过"),
        /** 编译未通过。 */
        COMPILE_FAILED("编译未通过"),
        /** 补丁一个都没落地。 */
        PATCH_FAILED("补丁未能应用"),
        /** 被安全策略拒绝。 */
        REJECTED_BY_SAFETY("被安全策略拒绝"),
        /** 被预算拦下。 */
        BUDGET_EXCEEDED("超出预算"),
        /** 模型输出无法解析。 */
        UNPARSEABLE("输出无法解析");

        private final String display;

        Status(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    public boolean successful() {
        return status == Status.SUCCEEDED;
    }

    /** 是否建议合并。 */
    public boolean mergeable() {
        return successful()
                && reviewComments.stream().noneMatch(ReviewComment::isBlocking);
    }

    /** 转成评测用的记录（便于把线上任务沉淀为评测集）。 */
    public TaskOutcome toTaskOutcome(String modelId) {
        return new TaskOutcome(taskId, modelId,
                patchesApplied, patchesApplied,
                compileResult != null && compileResult.success(),
                status == Status.SUCCEEDED,
                repairRounds,
                trace == null ? 0 : trace.totalTokens(),
                trace == null ? 0 : trace.totalElapsedMs(),
                status == Status.SUCCEEDED ? null : status.display());
    }

    @Override
    public String toString() {
        return String.format("CodeTaskResult{task=%s, 状态=%s, 补丁 %d 个, 变更 %d 个文件, "
                        + "可合并=%s}",
                taskId, status.display(), patchesApplied,
                bundle == null ? 0 : bundle.fileCount(), mergeable());
    }
}
