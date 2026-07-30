package com.zero.ai.agentstudy.day11humanintheloop.checkpointmanager;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 检查点存储的内存实现（教学 / 单机演示用）。
 *
 * <p>生产环境应替换为基于 Redis（快、适合活跃执行）或 PostgreSQL（持久、适合审计）的实现，
 * 只要实现 {@link CheckpointStore} 接口即可无缝替换——这正是端口/适配器架构的价值。</p>
 *
 * <p>并发说明：以 executionId 为键用 {@link ConcurrentHashMap} 分桶，每桶用
 * {@link CopyOnWriteArrayList} 承载该执行的检查点历史（写少读多、遍历安全）。</p>
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    /** checkpointId -> Checkpoint（精确查找用）。 */
    private final ConcurrentHashMap<String, Checkpoint> byId = new ConcurrentHashMap<>();

    /** executionId -> 该执行的检查点历史。 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Checkpoint>> byExecution = new ConcurrentHashMap<>();

    @Override
    public void save(Checkpoint checkpoint) {
        byId.put(checkpoint.checkpointId(), checkpoint);
        byExecution
                .computeIfAbsent(checkpoint.executionId(), k -> new CopyOnWriteArrayList<>())
                .add(checkpoint);
    }

    @Override
    public Optional<Checkpoint> findById(String checkpointId) {
        return Optional.ofNullable(byId.get(checkpointId));
    }

    @Override
    public Optional<Checkpoint> findLatest(String executionId) {
        CopyOnWriteArrayList<Checkpoint> list = byExecution.get(executionId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        // 取版本号最大的检查点
        return list.stream().max(Comparator.comparingLong(Checkpoint::version));
    }

    @Override
    public List<Checkpoint> history(String executionId) {
        CopyOnWriteArrayList<Checkpoint> list = byExecution.get(executionId);
        if (list == null) {
            return List.of();
        }
        // 按版本升序返回不可变副本
        return list.stream()
                .sorted(Comparator.comparingLong(Checkpoint::version))
                .toList();
    }

    @Override
    public void deleteAll(String executionId) {
        CopyOnWriteArrayList<Checkpoint> list = byExecution.remove(executionId);
        if (list != null) {
            list.forEach(cp -> byId.remove(cp.checkpointId()));
        }
    }
}