package com.zero.ai.agentstudy.day13officeagent.officecore.domain.port;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.context.TenantContext;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.task.OfficeTask;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.task.TaskStatus;

import java.util.List;
import java.util.Optional;

/**
 * 任务仓储端口（TaskRepository）——出站端口，聚合根 {@link OfficeTask} 的持久化抽象。
 *
 * <p><b>为什么需要它：</b> Office Agent 的一次作业往往是"长事务"——期间可能挂起等待人工审批、
 * 可能失败重试、可能被取消。这些状态必须<b>可持久化、可查询</b>，否则进程一重启任务就丢了。
 * 仓储端口把"任务存哪、怎么查"从领域与应用层剥离：领域层只管业务规则，应用层只管编排，
 * 至于底层是内存 Map、关系库、还是 Redis，全由适配器决定。这正是依赖倒置（DIP）的价值。</p>
 *
 * <p><b>多租户约束：</b> 除按 ID 精确查找外，所有列表查询都必须带 {@link TenantContext}，
 * 由适配器据此做租户隔离，杜绝 A 企业看到 B 企业任务的越权。</p>
 *
 * @author zero
 */
public interface TaskRepository {

    /**
     * 保存或更新一个任务（幂等 upsert：ID 已存在则覆盖）。
     *
     * @param task 待持久化的任务聚合根
     * @return 持久化后的任务（便于链式调用）
     */
    OfficeTask save(OfficeTask task);

    /**
     * 按 ID 精确查找任务。
     *
     * @param taskId 任务唯一标识
     * @return 命中则返回，否则为空
     */
    Optional<OfficeTask> findById(String taskId);

    /**
     * 列出某租户下的全部任务，按创建时间倒序（最新在前）。
     *
     * @param tenant 租户上下文
     * @return 该租户的任务列表（不可变，可能为空）
     */
    List<OfficeTask> findByTenant(TenantContext tenant);

    /**
     * 列出某租户下处于指定状态的任务，按创建时间倒序。
     *
     * @param tenant 租户上下文
     * @param status 目标状态
     * @return 匹配的任务列表（不可变，可能为空）
     */
    List<OfficeTask> findByTenantAndStatus(TenantContext tenant, TaskStatus status);

    /**
     * 删除一个任务。
     *
     * @param taskId 任务唯一标识
     * @return 确实删除了返回 {@code true}，原本不存在返回 {@code false}
     */
    boolean deleteById(String taskId);
}