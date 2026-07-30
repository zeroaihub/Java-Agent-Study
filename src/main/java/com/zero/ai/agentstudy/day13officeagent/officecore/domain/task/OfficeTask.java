package com.zero.ai.agentstudy.day13officeagent.officecore.domain.task;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.context.TenantContext;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentFormat;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 办公任务（OfficeTask）——DDD 聚合根，一次 Office Agent 作业的唯一事实来源。
 *
 * <p>它封装了"用户想要什么"（自然语言指令 {@code instruction}、期望产出格式 {@code targetFormats}）、
 * "任务归属谁"（{@link TenantContext}）、以及"任务现在是什么状态"（{@link TaskStatus} 状态机）。
 * 所有对任务状态的变更都必须经过聚合根方法，从而保证不变量——例如状态迁移必须合法、
 * 失败时必须携带错误原因。这是把业务规则收拢进领域模型、避免规则散落到各处 Service 的关键。</p>
 *
 * <p>典型指令示例："根据昨天销售数据生成一份周报，并制作 PPT，发送给销售总监，同时保存到知识库。"</p>
 *
 * @author zero
 */
public final class OfficeTask {

    private final String id;
    private final TenantContext tenant;
    private final String instruction;
    private final List<DocumentFormat> targetFormats;
    private final Instant createdAt;

    private TaskStatus status;
    private String failureReason;
    private Instant updatedAt;

    private OfficeTask(String id, TenantContext tenant, String instruction,
                       List<DocumentFormat> targetFormats) {
        this.id = id;
        this.tenant = Objects.requireNonNull(tenant, "tenant 不能为空");
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("instruction 不能为空");
        }
        this.instruction = instruction;
        this.targetFormats = targetFormats == null ? List.of() : List.copyOf(targetFormats);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = TaskStatus.CREATED;
    }

    /**
     * 工厂方法：创建一个新任务，自动生成 ID，初始状态为 CREATED。
     *
     * @param tenant        租户上下文
     * @param instruction   自然语言指令
     * @param targetFormats 期望产出的格式
     * @return 新任务
     */
    public static OfficeTask create(TenantContext tenant, String instruction,
                                    List<DocumentFormat> targetFormats) {
        return new OfficeTask("task-" + UUID.randomUUID(), tenant, instruction, targetFormats);
    }

    /** 开始执行：CREATED → RUNNING。 */
    public void start() {
        transition(TaskStatus.RUNNING);
    }

    /** 挂起等待人工审批：RUNNING → WAITING_APPROVAL。 */
    public void awaitApproval() {
        transition(TaskStatus.WAITING_APPROVAL);
    }

    /** 审批通过，恢复执行：WAITING_APPROVAL → RUNNING。 */
    public void resume() {
        transition(TaskStatus.RUNNING);
    }

    /** 标记成功完成。 */
    public void complete() {
        transition(TaskStatus.COMPLETED);
    }

    /**
     * 标记失败并记录原因。
     *
     * @param reason 失败原因
     */
    public void fail(String reason) {
        this.failureReason = reason;
        transition(TaskStatus.FAILED);
    }

    /** 取消任务。 */
    public void cancel() {
        transition(TaskStatus.CANCELLED);
    }

    private void transition(TaskStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "非法状态迁移：" + status + " → " + target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    /** 任务唯一标识。 */
    public String id() {
        return id;
    }

    /** 租户上下文。 */
    public TenantContext tenant() {
        return tenant;
    }

    /** 自然语言指令。 */
    public String instruction() {
        return instruction;
    }

    /** 期望产出格式列表（不可变）。 */
    public List<DocumentFormat> targetFormats() {
        return targetFormats;
    }

    /** 当前状态。 */
    public TaskStatus status() {
        return status;
    }

    /** 失败原因（仅在 FAILED 时有意义）。 */
    public String failureReason() {
        return failureReason;
    }

    /** 创建时间。 */
    public Instant createdAt() {
        return createdAt;
    }

    /** 最近更新时间。 */
    public Instant updatedAt() {
        return updatedAt;
    }
}