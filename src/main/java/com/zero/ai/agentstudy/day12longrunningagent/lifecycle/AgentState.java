package com.zero.ai.agentstudy.day12longrunningagent.lifecycle;

/**
 * Agent 生命周期状态枚举。
 *
 * <p>Long Running Agent 的核心之一就是"状态"。一个长任务在其生命周期内会在若干个
 * 明确定义的状态之间流转，任何非法流转都必须被状态机拒绝，以保证状态正确性。</p>
 *
 * <p>状态语义：</p>
 * <ul>
 *   <li>{@link #CREATED}   刚创建，尚未开始执行。</li>
 *   <li>{@link #RUNNING}   正在执行某个步骤。</li>
 *   <li>{@link #SUSPENDED} 已挂起（如等待人工审批 / 等待外部回调），可被 resume 唤醒。</li>
 *   <li>{@link #RETRYING}  某步失败后处于重试等待中。</li>
 *   <li>{@link #WAITING}   周期任务本轮完成，等待下一次定时触发。</li>
 *   <li>{@link #COMPLETED} 任务成功结束（终态）。</li>
 *   <li>{@link #FAILED}    任务失败结束（终态），通常伴随进入死信队列。</li>
 *   <li>{@link #CANCELLED} 被主动取消（终态）。</li>
 * </ul>
 *
 * @author ZeroAi
 */
public enum AgentState {

    /** 已创建，未启动。 */
    CREATED,

    /** 运行中。 */
    RUNNING,

    /** 已挂起，等待外部事件（审批/回调）唤醒。 */
    SUSPENDED,

    /** 重试中。 */
    RETRYING,

    /** 等待下一次定时触发（周期任务专用）。 */
    WAITING,

    /** 已成功完成（终态）。 */
    COMPLETED,

    /** 已失败（终态）。 */
    FAILED,

    /** 已取消（终态）。 */
    CANCELLED;

    /**
     * 是否为终态。终态不允许再流转到任何其他状态。
     *
     * @return true 表示终态
*/
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * 是否为"活跃"状态（需要被 Recovery 扫描并恢复的状态）。
     *
     * <p>进程崩溃后，处于这些状态的 Session 需要被恢复继续执行。</p>
     *
     * @return true 表示活跃（非终态）状态
     */
    public boolean isActive() {
        return !isTerminal();
    }
}