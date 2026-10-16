package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 工作流任务（聚合根）。
 *
 * <p>{@code WorkflowTask} 是一次多步骤任务的完整载体，聚合了：目标描述（{@code goal}）、
 * 有序步骤列表（{@link WorkflowStep}）、执行上下文（{@link ExecutionContext}）与整体状态
 * （{@link WorkflowStatus}）。它对应用户的一句自然语言指令，例如
 * 「帮我整理今天所有会议内容，生成纪要并发给项目组」。</p>
 *
 * <p>作为聚合根，它对外暴露「取下一个待执行步骤」「推进整体状态」等业务方法，
 * 而不允许外部直接篡改步骤集合，保证状态一致性。步骤本身按<strong>顺序</strong>执行
 * （本章聚焦顺序 + 条件跳过 + 重试 + 审批，并行编排在扩展阅读中讨论）。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public class WorkflowTask {

    private final String taskId;
    private final String goal;
    private final ExecutionContext context;
    private final List<WorkflowStep> steps;
    private final Instant createdAt;

    private WorkflowStatus status;

    /**
     * 构造工作流任务。
     *
     * @param taskId  任务唯一标识
     * @param goal    任务目标（自然语言）
     * @param context 执行上下文
     * @param steps   规划出的有序步骤
     */
    public WorkflowTask(String taskId, String goal, ExecutionContext context, List<WorkflowStep> steps) {
        this.taskId = Objects.requireNonNull(taskId, "taskId 不能为空");
        this.goal = Objects.requireNonNull(goal, "goal 不能为空");
        this.context = Objects.requireNonNull(context, "context 不能为空");
        this.steps = new ArrayList<>(steps == null ? List.of() : steps);
        this.createdAt = Instant.now();
        this.status = WorkflowStatus.CREATED;
    }

    /**
     * 工厂方法：创建一个新任务（自动生成 taskId）。
     *
     * @param goal    任务目标
     * @param context 执行上下文
     * @param steps   规划步骤
     * @return 任务实例
     */
    public static WorkflowTask create(String goal, ExecutionContext context, List<WorkflowStep> steps) {
        return new WorkflowTask("wf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                goal, context, steps);
    }

    /**
     * 取下一个待执行的步骤（第一个 PENDING 的步骤）。
     *
     * @return 待执行步骤的 Optional；若无则为空（任务可收尾）
     */
    public Optional<WorkflowStep> nextPendingStep() {
        return steps.stream()
                .filter(s -> s.status() == StepStatus.PENDING)
                .findFirst();
    }

    /**
     * 是否存在等待人工审批的步骤。
     *
     * @return true 表示任务应挂起等待审批
     */
    public boolean hasWaitingApproval() {
        return steps.stream().anyMatch(s -> s.status() == StepStatus.WAITING_APPROVAL);
    }

    /**
     * 取第一个等待审批的步骤。
     *
     * @return 等待审批步骤的 Optional
     */
    public Optional<WorkflowStep> firstWaitingApproval() {
        return steps.stream()
                .filter(s -> s.status() == StepStatus.WAITING_APPROVAL)
                .findFirst();
    }

    /**
     * 根据步骤 id 查找步骤。
     *
     * @param stepId 步骤标识
     * @return 步骤的 Optional
     */
    public Optional<WorkflowStep> findStep(String stepId) {
        return steps.stream().filter(s -> s.id().equals(stepId)).findFirst();
    }

    /**
     * 重新计算并推进整体状态。
     *
     * <p>规则：任一步骤 FAILED -> 任务 FAILED；存在 WAITING_APPROVAL -> SUSPENDED；
     * 所有步骤终态且无失败 -> COMPLETED；否则 RUNNING。</p>
     */
    public void refreshStatus() {
        if (status.isTerminal()) {
            return;
        }
        boolean anyFailed = steps.stream().anyMatch(s -> s.status() == StepStatus.FAILED);
        if (anyFailed) {
      this.status = WorkflowStatus.FAILED;
            return;
        }
        if (hasWaitingApproval()) {
            this.status = WorkflowStatus.SUSPENDED;
            return;
        }
        boolean allTerminal = steps.stream().allMatch(s -> s.status().isTerminal());
        this.status = allTerminal ? WorkflowStatus.COMPLETED : WorkflowStatus.RUNNING;
    }

    /** 标记任务开始执行。 */
    public void markRunning() {
        if (status == WorkflowStatus.CREATED || status == WorkflowStatus.SUSPENDED) {
            this.status = WorkflowStatus.RUNNING;
        }
    }

    /**
     * 汇总所有步骤产出，形成任务级结果摘要。
     *
     * @return 结果摘要文本
     */
    public String summarize() {
        StringBuilder sb = new StringBuilder();
        sb.append("任务[").append(taskId).append("] ").append(goal).append(" -> ").append(status).append("\n");
        for (WorkflowStep step : steps) {
            sb.append("  - ").append(step.name())
                    .append(" [").append(step.status()).append("]");
            if (step.output() != null) {
                sb.append(" 产出: ").append(step.output());
            }
            if (step.error() != null) {
                sb.append(" 错误: ").append(step.error());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** @return 任务唯一标识 */
    public String taskId() {
        return taskId;
    }

    /** @return 任务目标 */
    public String goal() {
        return goal;
    }

    /** @return 执行上下文 */
    public ExecutionContext context() {
        return context;
    }

    /** @return 步骤只读视图 */
    public List<WorkflowStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    /** @return 创建时间 */
    public Instant createdAt() {
        return createdAt;
    }

    /** @return 整体状态 */
    public WorkflowStatus status() {
        return status;
    }

    /** @return 步骤总数 */
    public int stepCount() {
        return steps.size();
    }
}