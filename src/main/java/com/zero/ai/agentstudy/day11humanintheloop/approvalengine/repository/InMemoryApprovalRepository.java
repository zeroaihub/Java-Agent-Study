package com.zero.ai.agentstudy.day11humanintheloop.approvalengine.repository;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版审批仓储实现。
 *
 * <p>用 {@link ConcurrentHashMap} 保证并发安全，适合教学、单测和单机 Demo。
 * 生产环境请替换为 Redis + PostgreSQL 实现，但接口不变。</p>
 *
 * <p>注意：内存实现意味着重启即丢，绝不能用于真实审批场景——审批记录必须持久化。</p>
 */
public class InMemoryApprovalRepository implements ApprovalRepository {

    /** requestId -> 审批请求。 */
    private final ConcurrentHashMap<String, ApprovalRequest> store = new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalRequest request) {
        store.put(request.getRequestId(), request);
    }

    @Override
    public Optional<ApprovalRequest> findById(String requestId) {
        return Optional.ofNullable(store.get(requestId));
    }

    @Override
    public List<ApprovalRequest> findByStatus(ApprovalStatus status) {
        return store.values().stream()
                .filter(r -> r.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<ApprovalRequest> findByTaskId(String taskId) {
        return store.values().stream()
                .filter(r -> r.getAction().taskId().equals(taskId))
                .collect(Collectors.toList());
    }

    @Override
    public void deleteById(String requestId) {
        store.remove(requestId);
    }

    @Override
    public long count() {
        return store.size();
    }
}