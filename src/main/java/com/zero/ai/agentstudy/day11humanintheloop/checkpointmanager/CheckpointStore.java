package com.zero.ai.agentstudy.day11humanintheloop.checkpointmanager;

import java.util.List;
import java.util.Optional;

/**
 * 检查点存储（Checkpoint Store）——出站端口（Outbound Port）。
 *
 * <p>本接口定义「检查点往哪存、怎么取」的抽象契约，但不关心底层是内存、Redis、
 * PostgreSQL 还是文件系统。CheckpointManager 依赖这个接口而非任何具体实现，
 * 遵循依赖倒置原则（DIP）：高层策略（何时快照、如何选取）与低层机制（存储介质）解耦。</p>
 *
 * <p>为什么要抽象成端口？——检查点的价值在于「跨进程、跨重启恢复」，这天然要求可持久化。
 * 但教学阶段先用内存实现跑通闭环，生产环境再换成 Redis/PG，业务代码零改动。
 * 这正是六边形架构给我们的可替换性。</p>
 */
public interface CheckpointStore {

    /**
     * 保存一个检查点。
     *
     * @param checkpoint 待保存的检查点（不可变）
     */
    void save(Checkpoint checkpoint);

    /**
     * 按检查点 ID 精确查找。
     *
     * @param checkpointId 检查点 ID
     * @return 检查点（可能不存在）
     */
    Optional<Checkpoint> findById(String checkpointId);

    /**
     * 取某执行的「最新」检查点（版本号最大者）——恢复时最常用。
     *
     * @param executionId 执行实例 ID
     * @return 最新检查点（可能不存在）
     */
    Optional<Checkpoint> findLatest(String executionId);

    /**
     * 列出某执行的全部检查点历史（按版本升序），用于回滚/审计。
     *
     * @param executionId 执行实例 ID
     * @return 检查点列表（不可变；无则返回空列表）
     */
    List<Checkpoint> history(String executionId);

    /**
     * 删除某执行的全部检查点（任务彻底结束、无需再恢复时清理）。
     *
     * @param executionId 执行实例 ID
     */
    void deleteAll(String executionId);
}