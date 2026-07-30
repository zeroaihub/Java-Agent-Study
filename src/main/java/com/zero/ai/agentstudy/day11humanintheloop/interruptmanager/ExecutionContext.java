package com.zero.ai.agentstudy.day11humanintheloop.interruptmanager;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行上下文（Execution Context）——Agent 一次执行的「现场」。
 *
 * <p>要实现「中断后能恢复」，就必须在中断的瞬间把执行现场保存下来：当前跑到第几步、
 * 已经产出了什么中间结果、下一步要做什么。本类就是这个「现场」的载体。</p>
 *
 * <p>与 Chapter 05 的 Checkpoint 的关系：Checkpoint 是把 ExecutionContext 在某一刻
 * 「快照 + 持久化」下来的产物；ExecutionContext 是运行时活着的对象。本章先把运行时
 * 现场建模清楚，下一章再讲如何把它冻结成可持久化的检查点。</p>
 *
 * <p>并发说明：一次 Agent 执行可能在多线程环境被读写（执行线程 + 中断线程），
 * 因此可变的 {@link #variables} 用 {@link ConcurrentHashMap} 承载。</p>
 */
public class ExecutionContext {

    /** 执行实例唯一 ID（一次 Agent 任务运行的标识）。 */
    private final String executionId;

    /** 所属任务 ID（对应 AgentAction.taskId）。 */
    private final String taskId;

    /** 创建时间。 */
    private final Instant createdAt;

    /** 当前执行状态。 */
    private volatile ExecutionState state;

    /** 当前执行到第几步（从 0 开始，用于恢复时定位断点）。 */
    private volatile int currentStep;

    /** 恢复时应从哪一步继续（默认等于 currentStep，可被人工修改任务时调整）。 */
    private volatile int resumeStep;

    /** 运行时变量 / 中间结果（键值对形式，供恢复时还原现场）。 */
    private final ConcurrentHashMap<String, Object> variables = new ConcurrentHashMap<>();

    public ExecutionContext(String executionId, String taskId) {
        this.executionId = Objects.requireNonNull(executionId, "executionId 不能为空");
        this.taskId = Objects.requireNonNull(taskId, "taskId 不能为空");
        this.createdAt = Instant.now();
        this.state = ExecutionState.RUNNING;
        this.currentStep = 0;
        this.resumeStep = 0;
    }

    /**
     * 从检查点还原用的构造器（供 CheckpointManager 调用，重建执行现场）。
     */
    public ExecutionContext(String executionId, String taskId, ExecutionState state,
                            int currentStep, int resumeStep, Map<String, Object> variables) {
        this.executionId = Objects.requireNonNull(executionId, "executionId 不能为空");
        this.taskId = Objects.requireNonNull(taskId, "taskId 不能为空");
        this.createdAt = Instant.now();
        this.state = Objects.requireNonNull(state, "state 不能为空");
        this.currentStep = currentStep;
        this.resumeStep = resumeStep;
        if (variables != null) {
            this.variables.putAll(variables);
        }
    }

    // ---------------- 状态与步进的受控变更 ----------------

    /** 应用新的执行状态（由 InterruptManager / ResumeEngine 调用）。 */
    public void transitTo(ExecutionState newState) {
        this.state = Objects.requireNonNull(newState);
    }

    /** 步进：执行完一步，推进步号。 */
    public void advanceStep() {
        this.currentStep++;
        this.resumeStep = this.currentStep;
    }

    /** 设置恢复断点（人工修改任务后，可能希望从某一步重跑）。 */
    public void setResumeStep(int resumeStep) {
        if (resumeStep < 0) {
            throw new IllegalArgumentException("resumeStep 不能为负：" + resumeStep);
        }
        this.resumeStep = resumeStep;
    }

    // ---------------- 变量存取 ----------------

    /** 写入一个运行时变量。 */
    public void putVariable(String key, Object value) {
        variables.put(Objects.requireNonNull(key), value);
    }

    /** 读取一个运行时变量。 */
    public Object getVariable(String key) {
        return variables.get(key);
    }

    /** 只读快照（用于 Checkpoint 冻结现场）。 */
    public Map<String, Object> snapshotVariables() {
        return Map.copyOf(variables);
    }

    // ---------------- getters ----------------

    public String getExecutionId() {
        return executionId;
    }

    public String getTaskId() {
        return taskId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ExecutionState getState() {
        return state;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public int getResumeStep() {
        return resumeStep;
    }
}