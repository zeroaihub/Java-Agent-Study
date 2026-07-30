package com.zero.ai.agentstudy.day11humanintheloop.approvalengine;

import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.AgentAction;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalRequest;
import com.zero.ai.agentstudy.day11humanintheloop.humancore.model.ApprovalStatus;

import java.util.Map;
import java.util.Optional;

/**
 * 审批引擎（Approval Engine）——审批业务的「用例入口（Inbound Port）」。
 *
 * <p>Chapter 02 我们造好了「零件」（模型 + 状态机 + 风险策略），本接口负责把这些零件
 * 组装成「可用的服务」：接收动作、评估风险、创建请求、驱动审批、落库持久化。</p>
 *
 * <p>它把「状态怎么流转」（交给状态机）、「请求存哪」（交给仓储）、「风险怎么算」
 * （交给策略）这三件事编排在一起，对上层（Controller / Agent）提供简单的动词方法。</p>
 */
public interface ApprovalEngine {

    /**
     * 提交一个动作，创建审批请求。
     * <p>引擎会用 RiskPolicy 评估风险、决定审批级数，并落库为 PENDING。</p>
     *
     * @param action Agent 想执行的动作
     * @return 新建的审批请求（已持久化）
     */
    ApprovalRequest submit(AgentAction action);

    /**
     * 批准某个审批请求（推进一级；多级会签下可能仍是 PENDING）。
     *
     * @param requestId 审批请求 ID
     * @param approver  审批人
     * @param comment   审批意见
     * @return 变更后的最新状态
     */
    ApprovalStatus approve(String requestId, String approver, String comment);

    /**
     * 驳回某个审批请求（进入终态 REJECTED）。
     */
    ApprovalStatus reject(String requestId, String approver, String comment);

    /**
     * 人工修改动作参数（进入 MODIFIED，等待重新提交）。
     *
     * @param modifiedParams 修改后的参数
     */
    ApprovalStatus modify(String requestId, String approver, String comment, Map<String, Object> modifiedParams);

    /**
     * 把一个 MODIFIED 的请求重新提交审批（回到 PENDING）。
     */
    ApprovalStatus resubmit(String requestId, String operator);

    /**
     * 主动终止（进入终态 ABORTED）。
     */
    ApprovalStatus abort(String requestId, String operator, String reason);

    /**
     * 查询审批请求。
     */
    Optional<ApprovalRequest> query(String requestId);
}