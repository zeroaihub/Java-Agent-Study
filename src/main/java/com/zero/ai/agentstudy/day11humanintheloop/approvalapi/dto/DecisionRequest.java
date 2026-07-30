package com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto;

import java.util.Map;

/**
 * 审批决策请求 DTO——承载「审批人对某个请求做出的一次决策」。
 *
 * <p>approve / reject / modify / resubmit / abort 这几个动作的入参高度相似
 * （都需要「谁操作的 + 一句意见」），因此用同一个 DTO 承载，靠 Controller 的不同
 * 端点区分语义，避免为每个动作重复定义几乎一样的 DTO。</p>
 *
 * <p>{@code modifiedParams} 仅在「修改动作参数（modify）」时使用，其他动作忽略即可。</p>
 *
 * @param operator       操作人（审批人 / 发起人），用于审计
 * @param comment        操作意见（审批理由、驳回原因等），会写入决策审计链
 * @param modifiedParams 修改后的动作参数（仅 modify 端点使用，可空）
 */
public record DecisionRequest(
        String operator,
        String comment,
        Map<String, Object> modifiedParams
) {

    /** 便捷读取：operator 为空时给一个占位，避免审计链出现 null。 */
    public String operatorOrAnonymous() {
        return (operator == null || operator.isBlank()) ? "anonymous" : operator;
    }
}