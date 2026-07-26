package com.zero.ai.agentstudy.day06workflow.workflow.core;

import lombok.Getter;
import lombok.ToString;

/**
 * NodeResult —— 单个节点执行完返回给引擎的「回执」。
 *
 * <p>教学要点：节点不直接决定流程走向，它只「汇报」自己干得如何，
 * 由引擎根据 status 决定下一步。这是「控制反转(IoC)」在流程层的体现。</p>
 *
 * <p>用静态工厂方法(success/completed/fail)代替 new，语义更清晰，
 * 这是《Effective Java》推荐的做法。</p>
 *
 * @author ZeroAi
 */
@Getter
@ToString
public class NodeResult {

    /** 该节点的执行状态 */
    private final NodeStatus status;

    /** 人类可读的说明信息（成功摘要或失败原因） */
    private final String message;

    /** 失败时携带的异常，成功时为 null */
    private final Throwable error;

    private NodeResult(NodeStatus status, String message, Throwable error) {
        this.status = status;
        this.message = message;
        this.error = error;
    }

    /** 成功，可继续下一节点 */
    public static NodeResult success(String message) {
        return new NodeResult(NodeStatus.SUCCESS, message, null);
    }

    /** 成功且流程结束 */
    public static NodeResult completed(String message) {
        return new NodeResult(NodeStatus.COMPLETED, message, null);
    }

    /** 失败（带原因） */
    public static NodeResult fail(String message) {
        return new NodeResult(NodeStatus.FAILED, message, null);
    }

    /** 失败（带异常） */
    public static NodeResult fail(String message, Throwable error) {
        return new NodeResult(NodeStatus.FAILED, message, error);
    }

    /** 挂起，等待人工介入 */
    public static NodeResult suspended(String message) {
   return new NodeResult(NodeStatus.SUSPENDED, message, null);
    }

    /** 便捷判断：是否成功（含 SUCCESS 与 COMPLETED） */
    public boolean isSuccess() {
        return status == NodeStatus.SUCCESS || status == NodeStatus.COMPLETED;
    }

    /** 便捷判断：流程是否应停止 */
    public boolean shouldStop() {
        return status == NodeStatus.COMPLETED
                || status == NodeStatus.FAILED
                || status == NodeStatus.SUSPENDED;
    }
}