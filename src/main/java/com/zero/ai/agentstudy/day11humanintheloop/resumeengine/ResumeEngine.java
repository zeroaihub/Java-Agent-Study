package com.zero.ai.agentstudy.day11humanintheloop.resumeengine;

import com.zero.ai.agentstudy.day11humanintheloop.interruptmanager.ExecutionContext;
import com.zero.ai.agentstudy.day11humanintheloop.interruptmanager.ExecutionState;
import com.zero.ai.agentstudy.day11humanintheloop.interruptmanager.InterruptManager;

import java.util.Objects;

/**
 * 恢复引擎（Resume Engine）。
 *
 * <p>与 {@link InterruptManager} 配对：Interrupt 负责「停下来」，Resume 负责「接着跑」。
 * 当人类做出决策（批准 / 修改后重提 / 补充输入）后，由本引擎把执行现场从挂起态
 * 切回 RUNNING，并告诉调用方应从哪一步继续。</p>
 *
 * <p>三种恢复语义：</p>
 * <ul>
 *   <li>{@link #resume(String)}：审批通过后原地恢复，从中断的下一步继续（Continue）。</li>
 *   <li>{@link #resumeFrom(String, int)}：人工修改任务后，从指定步重跑（Modify Task + Retry）。</li>
 *   <li>{@link #reject(String, String)}：审批驳回，执行进入 ABORTED（Reject）。</li>
 * </ul>
 *
 * <p>恢复的本质不是「代码指针跳转」，而是「状态复位 + 断点定位」：引擎把 ExecutionContext
 * 恢复到可执行状态，真正的「从第 N 步继续跑」由上层执行器读取 resumeStep 来驱动。</p>
 */
public class ResumeEngine {

    private final InterruptManager interruptManager;

    public ResumeEngine(InterruptManager interruptManager) {
        this.interruptManager = Objects.requireNonNull(interruptManager, "interruptManager 不能为空");
    }

    /**
     * 原地恢复：从中断点的下一步继续（Continue）。
     *
     * @param executionId 执行 ID
     * @return 恢复后的执行上下文（state=RUNNING）
     * @throws IllegalStateException 当前不处于挂起态，不能恢复
     */
    public ExecutionContext resume(String executionId) {
        ExecutionContext ctx = requireSuspended(executionId);
        // 先标记 RESUMED（瞬时态，便于审计/监控看到「刚被恢复」），再回到 RUNNING
        ctx.transitTo(ExecutionState.RESUMED);
        ctx.transitTo(ExecutionState.RUNNING);
        return ctx;
    }

    /**
     * 从指定步恢复：人工修改任务参数后，希望从某一步重跑（Modify Task + Retry）。
     *
     * @param executionId 执行 ID
     * @param fromStep    从第几步重新开始
     * @return 恢复后的执行上下文
     */
    public ExecutionContext resumeFrom(String executionId, int fromStep) {
        ExecutionContext ctx = requireSuspended(executionId);
        ctx.setResumeStep(fromStep);
        ctx.transitTo(ExecutionState.RESUMED);
        ctx.transitTo(ExecutionState.RUNNING);
        return ctx;
    }

    /**
     * 驳回：审批未通过，执行放弃（Reject）。
     *
     * @param executionId 执行 ID
     * @param reason      驳回原因
     * @return 进入 ABORTED 的执行上下文
     */
    public ExecutionContext reject(String executionId, String reason) {
        return interruptManager.abort(executionId, "审批驳回：" + reason);
    }

    /**
     * 标记执行正常完成（所有步骤跑完）。
     */
    public ExecutionContext complete(String executionId) {
        ExecutionContext ctx = interruptManager.query(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行现场不存在：" + executionId));
        if (ctx.getState().isTerminal()) {
            return ctx;
        }
        ctx.transitTo(ExecutionState.COMPLETED);
        return ctx;
    }

    /**
     * 加载并校验处于挂起态。
     */
    private ExecutionContext requireSuspended(String executionId) {
        ExecutionContext ctx = interruptManager.query(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行现场不存在：" + executionId));
        if (!ctx.getState().isSuspended()) {
            throw new IllegalStateException(
                    "执行不处于挂起态，无法恢复：" + executionId + " state=" + ctx.getState());
        }
        return ctx;
    }
}