package com.zero.ai.agentstudy.day13officeagent.officetask.adapter;

import com.zero.ai.agentstudy.day13officeagent.officecore.domain.context.TenantContext;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.port.TaskRepository;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.task.OfficeTask;
import com.zero.ai.agentstudy.day13officeagent.officecore.domain.task.TaskStatus;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存任务仓储（InMemoryTaskRepository）——{@link TaskRepository} 的默认适配器。
 *
 * <p><b>定位：</b> 这是一个"开箱即用"的参考实现，用 {@link ConcurrentHashMap} 承载任务，
 * 让整条 Pipeline 无需外部数据库即可跑通、便于教学与单测。生产环境可以再写一个 JPA/MyBatis
 * 或 Redis 适配器替换它——因为上层只依赖 {@link TaskRepository} 端口，替换<b>零改动业务代码</b>，
 * 这正是六边形架构\"适配器可插拔\"的直接体现。</p>
 *
 * <p><b>并发安全：</b> 使用 {@code ConcurrentHashMap} 保证多线程/虚拟线程下的读写安全；
 * 由于 {@link OfficeTask} 是可变聚合根，调用方在修改状态后必须再次 {@link #save} 回写，
 * 语义与关系库的 update 一致。</p>
 *
 * @author zero
 */
@Repository
public class InMemoryTaskRepository implements TaskRepository {

    /** taskId -> 任务聚合根。 */
    private final Map<String, OfficeTask> store = new ConcurrentHashMap<>();

    @Override
    public OfficeTask save(OfficeTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        store.put(task.id(), task);
        return task;
    }

    @Override
    public Optional<OfficeTask> findById(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public List<OfficeTask> findByTenant(TenantContext tenant) {
        String tenantId = tenant == null ? null : tenant.tenantId();
        if (tenantId == null) {
            return List.of();
        }
        return store.values().stream()
                .filter(t -> tenantId.equals(t.tenant().tenantId()))
                .sorted(Comparator.comparing(OfficeTask::createdAt).reversed())
                .toList();
    }

    @Override
    public List<OfficeTask> findByTenantAndStatus(TenantContext tenant, TaskStatus status) {
        String tenantId = tenant == null ? null : tenant.tenantId();
        if (tenantId == null || status == null) {
            return List.of();
        }
        return store.values().stream()
                .filter(t -> tenantId.equals(t.tenant().tenantId()))
                .filter(t -> t.status() == status)
                .sorted(Comparator.comparing(OfficeTask::createdAt).reversed())
                .toList();
    }

    @Override
    public boolean deleteById(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        return store.remove(taskId) != null;
    }
}