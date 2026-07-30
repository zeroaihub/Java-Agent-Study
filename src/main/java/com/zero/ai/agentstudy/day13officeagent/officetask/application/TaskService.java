package com.zero.ai.agentstudy.day13officeagent.officetask.application;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.context.TenantContext;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.ir.DocumentFormat;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.TaskRepository;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.task.OfficeTask;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.task.TaskStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 任务应用服务（TaskService）——officetask 模块的编排核心。
 *
 * <p><b>职责边界：</b> 本服务是"任务生命周期"的唯一入口。它把\"业务规则\"（状态迁移是否合法）
 * 留在聚合根 {@link OfficeTask} 内，自己只负责三件应用层的事：<b>取聚合根、调其方法、回写仓储</b>。
 * 这种"薄应用层 + 富领域模型"的分工，是 DDD 抵御业务逻辑泄漏到 Service 的关键——
 * 状态机永远不会被绕过，因为只有聚合根能改状态，而应用层每一步都必须经过它。</p>
 *
 * <p><b>为什么每个变更都要 {@link TaskRepository#save}：</b> {@link OfficeTask} 是内存中的可变对象，
 * 但仓储可能是数据库。修改状态后立即回写，保证内存视图与持久化视图一致，语义等同于关系库的
 * "load → mutate → update"。这一约定必须严格遵守，否则重启后会丢失最新状态。</p>
 *
 * <p><b>人工审批（Human-in-the-loop）：</b> {@link #requireApproval} 与 {@link #approve} 一对方法
 * 支撑了"敏感动作（如群发邮件、对外发布）必须人工点头"的企业合规诉求——任务挂起在
 * {@link TaskStatus#WAITING_APPROVAL}，等待外部信号后再 {@link #approve} 恢复执行。</p>
 *
 * @author zero
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;

    /**
     * 构造应用服务。
     *
     * @param taskRepository 任务仓储出站端口
     */
    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository 不能为空");
    }

    /**
     * 创建并持久化一个新任务，初始状态为 {@link TaskStatus#CREATED}。
     *
     * @param tenant        租户上下文
     * @param instruction   自然语言指令
     * @param targetFormats 期望产出格式
     * @return 已持久化的新任务
     */
    public OfficeTask createTask(TenantContext tenant, String instruction,
                                 List<DocumentFormat> targetFormats) {
        OfficeTask task = OfficeTask.create(tenant, instruction, targetFormats);
        return taskRepository.save(task);
    }

    /**
     * 开始执行任务：CREATED → RUNNING。
     *
     * @param taskId 任务标识
     * @return 更新后的任务
     */
    public OfficeTask start(String taskId) {
        OfficeTask task = require(taskId);
        task.start();
        return taskRepository.save(task);
    }

    /**
     * 挂起任务等待人工审批：RUNNING → WAITING_APPROVAL。
     *
     * @param taskId 任务标识
     * @return 更新后的任务
     */
    public OfficeTask requireApproval(String taskId) {
        OfficeTask task = require(taskId);
        task.awaitApproval();
        return taskRepository.save(task);
    }

    /**
     * 审批通过，恢复执行：WAITING_APPROVAL → RUNNING。
     *
     * @param taskId 任务标识
     * @return 更新后的任务
     */
    public OfficeTask approve(String taskId) {
        OfficeTask task = require(taskId);
        task.resume();
        return taskRepository.save(task);
    }

    /**
     * 标记任务成功完成。
     *
     * @param taskId 任务标识
     * @return 更新后的任务
     */
    public OfficeTask complete(String taskId) {
        OfficeTask task = require(taskId);
        task.complete();
        return taskRepository.save(task);
    }

    /**
     * 标记任务失败并记录原因。
     *
     * @param taskId 任务标识
     * @param reason 失败原因
     * @return 更新后的任务
     */
    public OfficeTask fail(String taskId, String reason) {
        OfficeTask task = require(taskId);
        task.fail(reason);
        return taskRepository.save(task);
    }

    /**
     * 取消任务。
     *
     * @param taskId 任务标识
     * @return 更新后的任务
     */
    public OfficeTask cancel(String taskId) {
        OfficeTask task = require(taskId);
        task.cancel();
        return taskRepository.save(task);
    }

    /**
     * 按 ID 查询任务。
     *
     * @param taskId 任务标识
     * @return 命中的任务
     * @throws NoSuchElementException 任务不存在时抛出
     */
    public OfficeTask get(String taskId) {
        return require(taskId);
    }

    /**
     * 列出某租户的全部任务（按创建时间倒序）。
     *
     * @param tenant 租户上下文
     * @return 任务列表
     */
    public List<OfficeTask> listByTenant(TenantContext tenant) {
        return taskRepository.findByTenant(tenant);
    }

    /**
     * 列出某租户下处于指定状态的任务（按创建时间倒序）。
     *
     * @param tenant 租户上下文
     * @param status 目标状态
     * @return 任务列表
     */
    public List<OfficeTask> listByStatus(TenantContext tenant, TaskStatus status) {
        return taskRepository.findByTenantAndStatus(tenant, status);
    }

    /**
     * 取聚合根，不存在则抛出。集中做空值防御，避免每个方法重复。
     *
     * @param taskId 任务标识
     * @return 任务聚合根
     */
    private OfficeTask require(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("任务不存在：" + taskId));
    }
}