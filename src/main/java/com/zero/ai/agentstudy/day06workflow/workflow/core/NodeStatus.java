package com.zero.ai.agentstudy.day06workflow.workflow.core;

/**
 * 单个节点(Node)执行完的状态。
 *
 * <p>教学要点：为什么要有统一的状态枚举？
 * 因为执行引擎(Engine)不认识任何具体业务，它只根据 NodeResult 里的状态
 * 决定「继续 / 停止 / 判失败」。状态统一，引擎才能统一调度。</p>
 *
 * @author ZeroAi
 */
public enum NodeStatus {

    /** 执行成功，可继续走下一个节点 */
    SUCCESS,

    /** 执行成功，且这是最后一步，流程正常结束 */
    COMPLETED,

    /** 执行失败（重试用尽后仍失败），流程终止 */
    FAILED,

    /** 挂起：等待人工介入(Human-in-the-loop)，流程暂停 */
    SUSPENDED
}