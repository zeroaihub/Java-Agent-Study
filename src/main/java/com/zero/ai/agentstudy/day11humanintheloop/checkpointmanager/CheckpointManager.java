package com.zero.ai.agentstudy.day11humanintheloop.checkpointmanager;

import com.zero.ai.agentstudy.day11humanintheloop.interruptmanager.ExecutionContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 检查点管理器（Checkpoint Manager）——编排者。
 *
 * <p>它是「运行时执行现场」与「持久化存储」之间的桥梁，负责三件事：</p>
 * <ol>
 *   <li><b>快照（checkpoint）</b>：在关键点把 {@link ExecutionContext} 冻结成 {@link Checkpoint} 并存起来；</li>
 *   <li><b>还原（restore）</b>：从最新检查点重建执行现场，实现跨重启恢复；</li>
 *   <li><b>版本管理</b>：为同一执行的多个检查点分配单调递增的版本号，支持选取最新与回滚。</li>
 * </ol>
 *
 * <p>依赖倒置：管理器依赖 {@link CheckpointStore} 接口，不关心底层是内存还是 Redis/PG。</p>
 *
 * <p>并发说明：版本号计数器用 {@link AtomicLong}，保证并发 checkpoint 时版本号不冲突。</p>
 */
public class CheckpointManager {

    private final CheckpointStore store;

    /** executionId -> 版本号计数器（保证同一执行的检查点版本单调递增）。 */
    private final ConcurrentHashMap<String, AtomicLong> versionSeq = new ConcurrentHashMap<>();

    public CheckpointManager(CheckpointStore store) {
        this.store = Objects.requireNonNull(store, "store 不能为空");
    }

    /**
     * 对当前执行现场打一个检查点。
     *
     * <p>建议在这些时机调用：中断前、恢复前、每完成一个关键步骤后。</p>
     *
     * @param ctx 执行现场
     * @return 刚生成的检查点
     */
    public Checkpoint checkpoint(ExecutionContext ctx) {
        Objects.requireNonNull(ctx, "ctx 不能为空");
        long version = versionSeq
                .computeIfAbsent(ctx.getExecutionId(), k -> new AtomicLong(0))
                .incrementAndGet();
        Checkpoint cp = Checkpoint.capture(ctx, version);
        store.save(cp);
        return cp;
    }

    /**
     * 从「最新检查点」还原执行现场（进程重启后恢复的入口）。
     *
     * @param executionId 执行实例 ID
     * @return 重建的执行现场（若无检查点则 empty）
     */
    public Optional<ExecutionContext> restoreLatest(String executionId) {
        return store.findLatest(executionId).map(this::rebuild);
    }

    /**
     * 回滚到指定版本的检查点并还原执行现场（用于「撤销到某个安全点」）。
     *
     * @param executionId 执行实例 ID
     * @param version     目标版本号
     * @return 重建的执行现场（若该版本不存在则 empty）
     */
    public Optional<ExecutionContext> rollbackTo(String executionId, long version) {
        return store.history(executionId).stream()
                .filter(cp -> cp.version() == version)
                .findFirst()
                .map(this::rebuild);
    }

    /**
     * 查看某执行的检查点历史（审计 / 排障）。
     */
    public List<Checkpoint> history(String executionId) {
        return store.history(executionId);
    }

    /**
     * 清理某执行的全部检查点（任务彻底结束后）。
     */
    public void clear(String executionId) {
        store.deleteAll(executionId);
        versionSeq.remove(executionId);
    }

    /**
     * 把不可变检查点重建成运行时的可变执行现场。
     */
    private ExecutionContext rebuild(Checkpoint cp) {
        return new ExecutionContext(
                cp.executionId(),
                cp.taskId(),
                cp.state(),
                cp.currentStep(),
                cp.resumeStep(),
                cp.variables());
    }
}