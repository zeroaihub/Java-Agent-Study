package com.zero.ai.agentstudy.day08multiagent.agent.core;

import com.zero.ai.agentstudy.day08multiagent.agent.memory.SharedMemory;
import com.zero.ai.agentstudy.day08multiagent.agent.message.Task;
import com.zero.ai.agentstudy.day08multiagent.entity.AgentExecutionLog;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentContext —— 贯穿一次协作的「公文包」。
 *
 * <p>教学要点：第二章讲过，AgentContext 是在架构图箭头上「流动」的对象。
 * Coordinator 创建它，然后依次交给每个 Agent；每个 Agent 从中拿到：</p>
 * <ul>
 *   <li>{@link #task}：我们在为什么目标工作（用户原始需求）；</li>
 *   <li>{@link #memory}：共享黑板（读上游产出、写自己的产出）；</li>
 *   <li>{@link #logs}：执行日志集合（可观测性，最终随响应返回）。</li>
 * </ul>
 *
 * <p>设计取舍：Context 只装「协作范围内需要共享」的东西，不装 Agent 私有的临时变量，
 * 避免第二章说的「上下文无限膨胀」。它是 SharedMemory 的宿主，但比 SharedMemory 多了
 * task 与 logs 这两个「协作级」信息。</p>
 *
 * @author ZeroAi
 */
@Getter
public class AgentContext {

    /** 本次协作要完成的总任务 */
    private final Task task;

    /** 共享记忆（黑板），所有 Agent 读写中间结果的地方 */
    private final SharedMemory memory;

    /** 执行日志集合，按执行顺序追加，用于全链路可观测性 */
    private final List<AgentExecutionLog> logs = new ArrayList<>();

    /** 当前是第几步（从 0 开始，每个 Agent 执行后递增），用于给日志编号 */
    private int step = 0;

    /**
     * 创建一个全新的协作上下文（Coordinator 在流程开始时调用）。
     *
     * @param task   总任务
     * @param memory 共享记忆
     */
    public AgentContext(Task task, SharedMemory memory) {
        this.task = task;
        this.memory = memory;
    }

    /**
     * 追加一条执行日志，并把步骤号 +1。
     *
     * <p>由 {@code AbstractAgent} 在模板方法里自动调用，业务 Agent 无需关心。</p>
     *
     * @param log 执行日志
     */
    public void addLog(AgentExecutionLog log) {
        this.step++;
        log.setStep(this.step);
        this.logs.add(log);
    }

    /**
     * 获取下一个步骤号（不改变状态），用于日志构造时的预取。
     *
     * @return 下一个步骤号
     */
    public int nextStep() {
        return this.step + 1;
    }
}