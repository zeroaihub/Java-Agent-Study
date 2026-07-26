package com.zero.ai.agentstudy.day06workflow.workflow.core;

/**
 * 整个 Workflow 实例(一次运行)的宏观状态。
 *
 * <p>教学要点：区分 {@link NodeStatus} 与 WorkflowState。
 * <ul>
 *   <li>NodeStatus：描述「某一个节点」这一步的结果。</li>
 *   <li>WorkflowState：描述「整条流程」当前处在生命周期的哪个阶段。</li>
 * </ul>
 * 状态流转：CREATED -> RUNNING -> (COMPLETED | FAILED | SUSPENDED)。</p>
 *
 * @author ZeroAi
 */
public enum WorkflowState {

    /** 已创建，尚未开始执行 */
    CREATED,

    /** 执行中 */
    RUNNING,

    /** 全部节点执行完毕，正常结束 */
    COMPLETED,

    /** 执行过程中失败终止 */
    FAILED,

    /** 挂起，等待外部（人工/事件）唤醒 */
    SUSPENDED
}