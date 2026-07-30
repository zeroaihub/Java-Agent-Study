package com.zero.ai.agentstudy.day11humanintheloop.interruptmanager;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 中断管理器（Interrupt Manager）。
 *
 * <p>负责管理所有活跃的 Agent 执行现场（{@link ExecutionContext}），并提供「打断」能力：
 * 当某个执行触发审批、被人工暂停或需要等待输入时，通过本管理器把它从 RUNNING 切到
 * 挂起态（INTERRUPTED / WAITING_APPROVAL），同时记录中断信号形成审计。</p>
 *
 * <p>职责边界：本管理器只负责「停下来 + 记录现场」，不负责「怎么恢复」——恢复是
 * {@link ResumeEngine} 的职责。二者配合完成完整的「挂起-恢复」闭环。</p>
 *
 * <p>并发说明：多个执行并发运行，用 {@link ConcurrentHashMap} 管理注册表；
 * 中断信号列表用 {@link CopyOnWriteArrayList}（读多写少，遍历安全）。</p>
 */
public class InterruptManager {

    /** executionId -> 执行上下文。 */
    private final ConcurrentHashMap<String, ExecutionContext> contexts = new ConcurrentHashMap<>();

    /** executionId -> 中断信号历史（审计）。 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<InterruptSignal>> signals = new ConcurrentHashMap<>();

    /**
     * 注册一个新的执行现场（Agent 开始执行时调用）。
     */
    public ExecutionContext register(String executionId, String taskId) {
        ExecutionContext ctx = new ExecutionContext(executionId, taskId);
        contexts.put(executionId, ctx);
        return ctx;
    }

    /**
     * 发起中断：把执行切到挂起态并记录信号。
     *
     * @param signal 中断信号
     * @return 更新后的执行上下文
     * @throws IllegalArgumentException 执行不存在
     * @throws IllegalStateException    执行已处于终态，无法中断
     */
    public ExecutionContext interrupt(InterruptSignal signal) {
        Objects.requireNonNull(signal, "signal 不能为空");
        ExecutionContext ctx = require(signal.executionId());
        if (ctx.getState().isTerminal()) {
            throw new IllegalStateException(
                    "执行已处于终态，无法中断：" + signal.executionId() + " state=" + ctx.getState());
        }
        // 审批触发 -> WAITING_APPROVAL；其它 -> INTERRUPTED
        ExecutionState target = (signal.reason() == InterruptReason.APPROVAL_REQUIRED)
                ? ExecutionState.WAITING_APPROVAL
                : ExecutionState.INTERRUPTED;
        ctx.transitTo(target);
        signals.computeIfAbsent(signal.executionId(), k -> new CopyOnWriteArrayList<>()).add(signal);
        return ctx;
    }

    /**
     * 主动终止一个执行（人工放弃或审批驳回后调用）。
     */
    public ExecutionContext abort(String executionId, String reason) {
        ExecutionContext ctx = require(executionId);
        if (ctx.getState().isTerminal()) {
            return ctx; // 已终态，幂等返回
        }
        ctx.transitTo(ExecutionState.ABORTED);
        signals.computeIfAbsent(executionId, k -> new CopyOnWriteArrayList<>())
                .add(InterruptSignal.forHumanPause(executionId, ctx.getCurrentStep(), "终止：" + reason));
        return ctx;
    }

    /**
     * 查询执行上下文。
     */
    public Optional<ExecutionContext> query(String executionId) {
        return Optional.ofNullable(contexts.get(executionId));
    }

    /**
     * 查询某执行的中断信号历史。
     */
    public List<InterruptSignal> signalsOf(String executionId) {
        CopyOnWriteArrayList<InterruptSignal> list = signals.get(executionId);
        return (list == null) ? List.of() : List.copyOf(list);
    }

    /**
     * 是否处于挂起态（供 ResumeEngine 校验能否恢复）。
     */
    public boolean isSuspended(String executionId) {
        ExecutionContext ctx = contexts.get(executionId);
        return ctx != null && ctx.getState().isSuspended();
    }

    /**
     * 移除执行现场（任务彻底结束后清理，可选）。
     */
    public void remove(String executionId) {
        contexts.remove(executionId);
        signals.remove(executionId);
    }

    /**
     * 加载并校验执行存在。
     */
    private ExecutionContext require(String executionId) {
        ExecutionContext ctx = contexts.get(executionId);
        if (ctx == null) {
            throw new IllegalArgumentException("执行现场不存在：" + executionId);
        }
        return ctx;
    }
}