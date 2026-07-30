package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 审批决策（值对象 / Value Object）。
 *
 * <p>代表"某个审批人，在某个时刻，对某个审批请求，做了一个什么决定"。
 * 它是审批流程中的一次不可变事件记录——审计日志会直接落这个结构。</p>
 *
 * @param approver     审批人标识（用户名 / userId）
 * @param transition   本次决策对应的流转动作（APPROVE / REJECT / MODIFY ...）
 * @param comment      审批意见（可选，驳回时强烈建议填写原因）
 * @param modifiedParams 人工修改后的参数（仅当 transition == MODIFY 时有意义）
 * @param decidedAt    决策时间
 */
public record ApprovalDecision(
        String approver,
        ApprovalTransition transition,
        String comment,
        Map<String, Object> modifiedParams,
        Instant decidedAt
) {

    /**
     * 紧凑构造器：非空校验 + 防御性拷贝 + 默认时间。
     */
    public ApprovalDecision {
        Objects.requireNonNull(approver, "approver 不能为空");
        Objects.requireNonNull(transition, "transition 不能为空");
        modifiedParams = (modifiedParams == null) ? Map.of() : Map.copyOf(modifiedParams);
        decidedAt = (decidedAt == null) ? Instant.now() : decidedAt;
    }

    /**
     * 便捷工厂：批准。
     */
    public static ApprovalDecision approve(String approver, String comment) {
        return new ApprovalDecision(approver, ApprovalTransition.APPROVE, comment, Map.of(), Instant.now());
    }

    /**
     * 便捷工厂：驳回。
     */
    public static ApprovalDecision reject(String approver, String comment) {
        return new ApprovalDecision(approver, ApprovalTransition.REJECT, comment, Map.of(), Instant.now());
    }

    /**
     * 便捷工厂：人工修改。
     */
    public static ApprovalDecision modify(String approver, String comment, Map<String, Object> modifiedParams) {
        return new ApprovalDecision(approver, ApprovalTransition.MODIFY, comment, modifiedParams, Instant.now());
    }

    /**
     * 是否为通过类决策（APPROVE / FINALIZE / NEXT_LEVEL）。
     */
    public boolean isApproval() {
        return transition == ApprovalTransition.APPROVE
                || transition == ApprovalTransition.FINALIZE
                || transition == ApprovalTransition.NEXT_LEVEL;
    }

    /**
     * 是否为驳回决策。
     */
    public boolean isRejection() {
        return transition == ApprovalTransition.REJECT;
    }
}