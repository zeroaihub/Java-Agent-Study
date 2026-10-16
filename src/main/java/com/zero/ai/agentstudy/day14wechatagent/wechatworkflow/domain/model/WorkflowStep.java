package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model;

import java.util.Objects;

/**
 * 工作流单步（Step）实体。
 *
 * <p>一个 {@code WorkflowStep} 描述「一件要做的事」，包含：</p>
 * <ul>
 *     <li>{@code id} —— 步骤唯一标识（任务内有序）。</li>
 *     <li>{@code name} —— 人类可读名称，如「生成会议纪要」。</li>
 *     <li>{@code action} —— 动作类型，供 {@code StepExecutor} 路由到具体执行器，
 *         如 {@code office.summarize}、{@code browser.upload}。</li>
 *     <li>{@code requiresApproval} —— 是否为需人工审批的关键步骤（Human-in-the-Loop）。</li>
 *     <li>{@code maxRetries} —— 失败最大重试次数。</li>
 * </ul>
 *
 * <p>它是<strong>可变实体</strong>：随着执行推进，{@code status}、{@code attempts}、
 * {@code output}、{@code error} 会被引擎更新。状态流转由引擎驱动，见 {@link StepStatus}。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public class WorkflowStep {

    private final String id;
    private final String name;
    private final String action;
    private final boolean requiresApproval;
    private final int maxRetries;

    private StepStatus status;
    private int attempts;
    private String output;
    private String error;

    /**
     * 人工审批放行标记。
     *
     * <p>仅对 {@code requiresApproval == true} 的关键步骤有意义。审批通过后由引擎置为
     * {@code true}，使该步骤在下一轮调度时不再被审批拦截，从而从挂起点继续执行。
     * 这解决了「审批放行后 nextPendingStep 再次命中该步骤导致二次挂起」的问题。</p>
     */
    private boolean approved;

    /**
     * 完整构造器。
     *
     * @param id               步骤唯一标识
     * @param name             步骤名称
     * @param action           动作类型
     * @param requiresApproval 是否需人工审批
     * @param maxRetries       最大重试次数（>=0）
     */
    public WorkflowStep(String id, String name, String action, boolean requiresApproval, int maxRetries) {
        this.id = Objects.requireNonNull(id, "step id 不能为空");
      this.name = Objects.requireNonNull(name, "step name 不能为空");
        this.action = Objects.requireNonNull(action, "step action 不能为空");
        this.requiresApproval = requiresApproval;
        this.maxRetries = Math.max(0, maxRetries);
        this.status = StepStatus.PENDING;
        this.attempts = 0;
    }

    /**
     * 便捷工厂：普通步骤（无需审批，重试 1 次）。
     *
     * @param id     步骤标识
     * @param name   步骤名称
     * @param action 动作类型
     * @return 步骤实例
     */
    public static WorkflowStep of(String id, String name, String action) {
        return new WorkflowStep(id, name, action, false, 1);
    }

    /**
     * 便捷工厂：需人工审批的关键步骤。
     *
     * @param id     步骤标识
     * @param name   步骤名称
     * @param action 动作类型
     * @return 步骤实例
     */
    public static WorkflowStep approvalStep(String id, String name, String action) {
        return new WorkflowStep(id, name, action, true, 0);
    }

    /** 标记进入执行中，并累加尝试次数。 */
    public void markRunning() {
        this.status = StepStatus.RUNNING;
        this.attempts++;
    }

    /**
     * 标记成功，并记录产出。
     *
     * @param output 步骤产出摘要
     */
    public void markSucceeded(String output) {
        this.status = StepStatus.SUCCEEDED;
        this.output = output;
        this.error = null;
    }

    /**
     * 标记失败，并记录错误。
     *
     * @param error 错误信息
     */
    public void markFailed(String error) {
        this.status = StepStatus.FAILED;
        this.error = error;
    }

    /** 标记为跳过（条件分支判定不需执行）。 */
    public void markSkipped() {
        this.status = StepStatus.SKIPPED;
    }

    /** 标记为等待人工审批。 */
    public void markWaitingApproval() {
        this.status = StepStatus.WAITING_APPROVAL;
    }

    /**
     * 人工审批放行：置放行标记并重置为待执行，供引擎从挂起点续跑。
     *
     * <p>置 {@code approved = true} 后，引擎在下一轮 {@code isApprovalPending()} 判断中
     * 将放行该步骤，不再二次挂起。</p>
     */
    public void approve() {
        this.approved = true;
        this.status = StepStatus.PENDING;
    }

    /**
     * 是否为「尚未放行的审批步骤」。
     *
     * <p>引擎据此决定是否挂起：需审批 <strong>且</strong> 尚未放行时才拦截。审批通过后
     * {@code approved} 为 {@code true}，此方法返回 {@code false}，步骤得以继续执行。</p>
     *
     * @return true 表示需拦截挂起
     */
    public boolean isApprovalPending() {
        return requiresApproval && !approved;
    }

    /**
     * 是否还可以重试（尝试次数未超过 maxRetries）。
     *
     * @return true 表示可再试一次
     */
    public boolean canRetry() {
        return attempts <= maxRetries;
    }

    /** 重置为待执行（用于审批通过后重新调度）。 */
    public void resetToPending() {
        this.status = StepStatus.PENDING;
    }

    /** @return 步骤唯一标识 */
    public String id() {
        return id;
    }

    /** @return 步骤名称 */
    public String name() {
        return name;
    }

    /** @return 动作类型 */
    public String action() {
        return action;
    }

    /** @return 是否需人工审批 */
    public boolean requiresApproval() {
        return requiresApproval;
    }

    /** @return 审批是否已放行 */
    public boolean approved() {
        return approved;
    }

    /** @return 最大重试次数 */
    public int maxRetries() {
        return maxRetries;
    }

    /** @return 当前状态 */
    public StepStatus status() {
        return status;
    }

    /** @return 已尝试次数 */
    public int attempts() {
        return attempts;
    }

    /** @return 步骤产出（可能为 null） */
    public String output() {
        return output;
    }

    /** @return 错误信息（可能为 null） */
    public String error() {
        return error;
    }

    @Override
    public String toString() {
        return "WorkflowStep{id='" + id + "', name='" + name + "', action='" + action
                + "', status=" + status + ", attempts=" + attempts + "}";
    }
}