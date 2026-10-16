package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowTask;

import java.util.Optional;

/**
 * 工作流任务仓储端口。
 *
 * <p>为什么工作流任务必须持久化？因为它可能<strong>跨越很长的生命周期</strong>：
 * 一个任务可能在「等待人工审批」（SUSPENDED）状态挂起数小时甚至数天，
 * 期间进程可能重启。若只存在内存里，重启即丢失，审批放行后无从恢复。</p>
 *
 * <p>本模块用 {@code InMemoryWorkflowRepository} 演示；生产期替换为
 * PostgreSQL（JSONB 存 steps + blackboard）或 Redis。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public interface WorkflowRepository {

    /**
     * 保存或更新工作流任务（upsert 语义）。
     *
     * @param task 工作流任务，不能空
     */
    void save(WorkflowTask task);

    /**
     * 按 taskId 查找任务。
     *
     * @param taskId 任务 ID
     * @return 任务（可能不存在）
     */
    Optional<WorkflowTask> findById(String taskId);

    /**
     * 删除任务（可选，用于清理已完成任务）。
     *
     * @param taskId 任务 ID
     */
    void deleteById(String taskId);
}