package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowTask;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port.WorkflowRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版工作流任务仓储（教学/测试实现）。
 *
 * <p>用 {@link ConcurrentHashMap} 存储任务，进程重启即丢失。生产期请替换为
 * PostgreSQL（用 JSONB 列持久化 steps 与 blackboard，便于挂起-恢复跨越进程生命周期）。</p>
 *
 * <p><strong>注意：</strong>这里保存的是 {@link WorkflowTask} 对象引用本身，
 * 因此引擎对任务的原地修改会即时反映到仓储中（内存实现的特性）。
 * 换成数据库实现时，save 会真正序列化落库，findById 会反序列化重建对象。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Repository
public class InMemoryWorkflowRepository implements WorkflowRepository {

    private final Map<String, WorkflowTask> store = new ConcurrentHashMap<>();

    @Override
    public void save(WorkflowTask task) {
        if (task != null) {
            store.put(task.taskId(), task);
        }
    }

    @Override
    public Optional<WorkflowTask> findById(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public void deleteById(String taskId) {
        store.remove(taskId);
    }
}