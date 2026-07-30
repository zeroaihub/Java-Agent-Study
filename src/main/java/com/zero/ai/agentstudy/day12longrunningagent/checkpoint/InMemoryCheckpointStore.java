package com.zero.ai.agentstudy.day12longrunningagent.checkpoint;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CheckpointStore 的内存实现（默认，零依赖）。
 *
 * <p>用 {@code Map<sessionId, List<Checkpoint>>} 保存每个 Session 的检查点历史。
 * value 用 {@link CopyOnWriteArrayList} 保证并发追加与读取安全。</p>
 */
@Component
public class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentHashMap<String, List<Checkpoint>> store = new ConcurrentHashMap<>();

    @Override
    public void append(Checkpoint checkpoint) {
        store.computeIfAbsent(checkpoint.getSessionId(), k -> new CopyOnWriteArrayList<>())
                .add(checkpoint);
    }

    @Override
    public Optional<Checkpoint> findLatest(String sessionId) {
        List<Checkpoint> list = store.get(sessionId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(list.size() - 1));
    }

    @Override
    public List<Checkpoint> findAll(String sessionId) {
        List<Checkpoint> list = store.get(sessionId);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    @Override
    public void deleteBySession(String sessionId) {
        store.remove(sessionId);
    }
}