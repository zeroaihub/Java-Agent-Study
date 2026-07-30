package com.zero.ai.agentstudy.day11humanintheloop.checkpointmanager;

import com.zero.ai.agentstudy.day11humanintheloop.interruptmanager.ExecutionContext;
import com.zero.ai.agentstudy.day11humanintheloop.interruptmanager.ExecutionState;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 检查点（Checkpoint）——{@link ExecutionContext} 在某一时刻的「不可变快照」。
 *
 * <p>Chapter 04 我们把执行过程建模成了运行时的 {@link ExecutionContext}，但它只活在内存里：
 * 进程一旦重启，所有挂起中的执行现场就全部丢失。Checkpoint 的使命就是把这个运行时现场
 * 「冻结」成一个不可变、可持久化、可跨进程还原的快照。</p>
 *
 * <p>为什么用 {@code record}？——检查点是「过去某一刻的事实」，一旦产生就绝不允许被修改，
 * 否则「恢复到检查点」就失去了确定性。不可变性是检查点可信的根基。</p>
 *
 * <p>关键设计：Checkpoint 只保存「恢复所需的最小充分状态」——执行标识、状态、步号、
 * 变量快照 + 一个单调递增的版本号。它不持有对 ExecutionContext 的引用，
 * 从而与运行时对象彻底解耦，可以安全地序列化、落库、跨网络传输。</p>
 *
 * @param checkpointId 检查点唯一 ID
 * @param executionId  所属执行实例 ID
 * @param taskId       所属任务 ID
 * @param version      版本号（同一执行的检查点单调递增，用于选取最新/回滚）
 * @param state        快照时刻的执行状态
 * @param currentStep  快照时刻实际跑到的步号
 * @param resumeStep   快照时刻的恢复断点
 * @param variables    快照时刻的运行时变量（不可变副本）
 * @param createdAt    检查点创建时间
 */
public record Checkpoint(
        String checkpointId,
        String executionId,
        String taskId,
        long version,
        ExecutionState state,
        int currentStep,
        int resumeStep,
        Map<String, Object> variables,
        Instant createdAt) {

    /**
     * 紧凑构造器：非空校验 + 变量做防御性只读拷贝。
     */
    public Checkpoint {
        Objects.requireNonNull(checkpointId, "checkpointId 不能为空");
        Objects.requireNonNull(executionId, "executionId 不能为空");
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(state, "state 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        // 防御性拷贝：即使外部传入可变 Map，检查点内部也是只读的
        variables = (variables == null) ? Map.of() : Map.copyOf(variables);
    }

    /**
     * 从运行时 {@link ExecutionContext} 冻结出一个检查点。
     *
     * @param ctx     执行现场
     * @param version 版本号（由 CheckpointManager 分配）
     * @return 不可变检查点
     */
    public static Checkpoint capture(ExecutionContext ctx, long version) {
        Objects.requireNonNull(ctx, "ctx 不能为空");
        return new Checkpoint(
                ctx.getExecutionId() + "-cp-" + version,
                ctx.getExecutionId(),
                ctx.getTaskId(),
                version,
                ctx.getState(),
                ctx.getCurrentStep(),
                ctx.getResumeStep(),
                ctx.snapshotVariables(),
                Instant.now());
    }
}