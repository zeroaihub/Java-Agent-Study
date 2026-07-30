package com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalDecision;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;

import java.time.Instant;
import java.util.List;

/**
 * 审批视图响应 DTO——把领域聚合根 {@code ApprovalRequest} 「投影」成前端好消费的扁平结构。
 *
 * <p>这是「读模型（Read Model）」的思路：领域对象为「写」而设计（带状态机、审计链、
 * 受控变更方法），但前端「读」的时候只想要一坨拍平的、能直接渲染成表格的字段。
 * 硬把领域对象序列化给前端，会暴露内部方法、循环引用、懒加载等一堆麻烦。用一个专门
 * 的视图 DTO 做投影，读写分离，各自干净。</p>
 *
 * <p>提供 {@link #from(ApprovalRequest)} 静态工厂完成「领域对象 → 视图」的转换，
 * 把投影逻辑收拢在一处，Controller 只管调用。</p>
 *
 * @param requestId      审批请求 ID
 * @param taskId         关联任务 ID
 * @param actionType     动作类型
 * @param description    动作描述
 * @param riskLevel      风险等级
 * @param status         当前状态
 * @param terminal       是否已到终态（前端据此禁用操作按钮）
 * @param requiredLevels 需要的审批级数
 * @param approvedLevels 已通过的级数
 * @param createdAt      发起时间
 * @param expireAt       超时时间点
 * @param decisions      决策审计链（已投影为视图）
 */
public record ApprovalView(
        String requestId,
        String taskId,
        String actionType,
        String description,
        String riskLevel,
        String status,
        boolean terminal,
        int requiredLevels,
        int approvedLevels,
        Instant createdAt,
        Instant expireAt,
        List<DecisionView> decisions
) {

    /**
     * 决策审计链的视图投影（一条决策 → 一行审计记录）。
     *
     * @param approver   审批人
     * @param transition 流转动作（APPROVE / REJECT / MODIFY ...）
     * @param comment    审批意见
     * @param decidedAt  决策时间
     */
    public record DecisionView(
            String approver,
            String transition,
            String comment,
            Instant decidedAt
    ) {
        static DecisionView from(ApprovalDecision d) {
            return new DecisionView(
                    d.approver(),
                    d.transition().name(),
                    d.comment(),
                    d.decidedAt()
            );
        }
    }

    /**
     * 领域对象 → 视图 DTO 的投影工厂。
     *
     * @param req 审批请求聚合根
     * @return 扁平化的视图 DTO
     */
    public static ApprovalView from(ApprovalRequest req) {
        List<DecisionView> decisionViews = req.getDecisions().stream()
                .map(DecisionView::from)
                .toList();
        return new ApprovalView(
                req.getRequestId(),
                req.getAction().taskId(),
                req.getAction().type(),
                req.getAction().description(),
                req.getRiskLevel().name(),
                req.getStatus().name(),
                req.getStatus().isTerminal(),
                req.getRequiredLevels(),
                req.getApprovedLevels(),
                req.getCreatedAt(),
                req.getExpireAt(),
                decisionViews
        );
    }
}