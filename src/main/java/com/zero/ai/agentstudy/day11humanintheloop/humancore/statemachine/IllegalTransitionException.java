package com.zero.ai.agentstudy.day11humanintheloop.humancore.statemachine;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalTransition;

/**
 * 非法流转异常。
 *
 * <p>当有人试图对审批请求做一个"当前状态不允许"的操作时抛出。
 * 例如：一个已经 REJECTED 的请求，还想再 APPROVE，这在业务上是荒谬的，
 * 必须显式拒绝而不是默默放过——这正是状态机存在的意义。</p>
 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(ApprovalStatus from, ApprovalTransition transition) {
        super("非法状态流转：状态[" + from + "] 不允许执行动作[" + transition + "]");
    }
}