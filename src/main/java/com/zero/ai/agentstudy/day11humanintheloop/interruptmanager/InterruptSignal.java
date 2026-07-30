package com.zero.ai.agentstudy.day11humanintheloop.interruptmanager;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;

import java.time.Instant;
import java.util.Objects;

/**
 * 中断信号（Interrupt Signal）——值对象。
 *
 * <p>当 Agent 需要停下来（触发审批、人工干预、等待输入）时，会产生一个中断信号。
 * 它记录了「为什么停」「在哪一步停」「针对哪个动作停」，是中断事件的不可变凭证。</p>
 *
 * @param executionId 被中断的执行实例 ID
 * @param reason      中断原因
 * @param atStep      在第几步被中断
 * @param triggerAction 触发中断的动作（可为 null，如纯人工暂停时无特定动作）
 * @param message     人类可读的中断说明
 * @param signaledAt  信号产生时间
 */
public record InterruptSignal(
        String executionId,
        InterruptReason reason,
        int atStep,
        AgentAction triggerAction,
        String message,
        Instant signaledAt
) {

    public InterruptSignal {
        Objects.requireNonNull(executionId, "executionId 不能为空");
        Objects.requireNonNull(reason, "reason 不能为空");
        if (signaledAt == null) {
            signaledAt = Instant.now();
        }
        if (message == null) {
            message = "";
        }
    }

    /** 便捷工厂：因审批触发的中断。 */
    public static InterruptSignal forApproval(String executionId, int atStep, AgentAction action) {
        return new InterruptSignal(executionId, InterruptReason.APPROVAL_REQUIRED, atStep, action,
                "动作触发审批网关，等待人工审批", Instant.now());
    }

    /** 便捷工厂：人工主动暂停。 */
    public static InterruptSignal forHumanPause(String executionId, int atStep, String message) {
        return new InterruptSignal(executionId, InterruptReason.HUMAN_INTERVENTION, atStep, null,
                message, Instant.now());
    }
}