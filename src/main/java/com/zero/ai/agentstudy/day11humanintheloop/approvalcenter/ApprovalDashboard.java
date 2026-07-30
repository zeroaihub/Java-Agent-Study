package com.zero.ai.agentstudy.day11humanintheloop.approvalcenter;

import java.util.List;
import java.util.Map;

/**
 * 企业审批中心仪表盘——全模块能力的「读模型」聚合视图。
 *
 * <p>Day 11 的前九章把审批引擎、状态机、多级会签、反馈学习、REST API、ERP 实战一块块搭好了。
 * 本章不再新增任何内核能力，而是站在这些能力之上，提供一个「运营视角」的只读仪表盘：
 * 一眼看清「现在有多少待办、多少已通过、多少被驳回、有没有超时」，这是任何企业审批系统
 * 上线后运营团队最先要的那块屏。</p>
 *
 * <p>这是典型的 <b>CQRS 读侧</b>思路：写侧（审批引擎）负责严格的状态流转，读侧（本仪表盘）
 * 只做聚合投影，两者互不干扰。仪表盘是纯统计，绝不触发任何状态变更。</p>
 *
 * @param total          审批请求总数
 * @param pending        待办数（PENDING）
 * @param approved       已通过（单级终态 / 多级中间态 APPROVED）
 * @param finalApproved  多级会签全部通过（FINAL_APPROVED）
 * @param rejected       已驳回（REJECTED）
 * @param modified       待重新提交（MODIFIED）
 * @param timeout        已超时（TIMEOUT）
 * @param aborted        已终止（ABORTED）
 * @param terminalCount  已到终态的总数（不可再流转）
 * @param statusBreakdown 按状态名分组的原始计数（便于前端灵活渲染）
 * @param riskBreakdown  按风险等级分组的计数
 * @param recentPending  最近若干条待办摘要（控制台首页直接可渲染）
 */
public record ApprovalDashboard(
        long total,
        long pending,
        long approved,
        long finalApproved,
        long rejected,
        long modified,
        long timeout,
        long aborted,
        long terminalCount,
        Map<String, Long> statusBreakdown,
        Map<String, Long> riskBreakdown,
        List<PendingSummary> recentPending
) {

    /**
     * 待办摘要——控制台首页每一行「等我处理」的卡片。
     *
     * @param requestId   审批请求 ID
     * @param actionType  动作类型
     * @param description 动作描述
     * @param riskLevel   风险等级
     * @param requiredLevels 需要的审批级数
     * @param approvedLevels 已通过级数
     */
    public record PendingSummary(
            String requestId,
            String actionType,
            String description,
            String riskLevel,
            int requiredLevels,
            int approvedLevels
    ) {
    }
}