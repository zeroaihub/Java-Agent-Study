package com.zero.ai.agentstudy.day12longrunningagent.scheduler;

import com.zero.ai.agentstudy.day12longrunningagent.queue.AgentTask;

/**
 * 任务处理器（策略接口）。
 *
 * <p>调度器（{@link TaskDispatcher}）从队列拉到任务后，需要按 {@code task.type}
 * 分发给对应的处理器执行。业务方只需实现本接口并声明自己关心的类型，
 * 即可插入执行流水线——这是一种典型的 <b>策略模式 + 开闭原则</b> 落地：
 * 新增任务类型只需新增 Handler，无需改动调度器主循环。</p>
 */
public interface TaskHandler {

    /**
     * 该处理器支持的任务类型（与 {@link AgentTask#getType()} 匹配）。
     */
    String supportType();

    /**
     * 执行任务。
     *
     * <p>约定：正常返回代表成功；抛出任何异常代表失败，
     * 由调度器按 {@code RetryPolicy} 决定重试或投递死信队列。</p>
     *
     * @param task 待处理任务
     * @throws Exception 处理失败时抛出，触发重试/死信逻辑
     */
    void handle(AgentTask task) throws Exception;
}