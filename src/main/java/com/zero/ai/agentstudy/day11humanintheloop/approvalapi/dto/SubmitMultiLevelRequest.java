package com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 提交多级会签的请求 DTO——在 {@link SubmitApprovalRequest} 的基础上，额外携带一条「审批链定义」。
 *
 * <p>单级审批只需描述「做什么动作」，风险与级数由引擎内部推断；而多级会签必须由调用方
 * <b>显式声明</b>「几级、每级谁批、每级多久超时」，因为这是一条业务流程配置，Controller
 * 无法凭空发明。因此本 DTO 内嵌一个 {@link LevelSpec} 列表，Controller 会把它翻译成领域层的
 * {@code ApprovalChain / ApprovalLevel}。</p>
 *
 * <p><b>防腐层职责：</b>前端只提供「扁平、易填」的层级描述（级号、角色名、审批人名单、超时秒数），
 * 领域层的校验（级号必须 1..N 连续、审批人非空、超时为正）由 {@code ApprovalChain / ApprovalLevel}
 * 的紧凑构造器兜底——DTO 不重复校验逻辑，只做结构承载。</p>
 *
 * @param taskId      任务 ID
 * @param actionType  动作类型
 * @param description 动作的人类可读描述
 * @param amount      涉及金额（可空）
 * @param params      动作参数（可空）
 * @param riskLevel   风险等级（NONE / LOW / HIGH；多级会签一般为 HIGH，可空则默认 HIGH）
 * @param chainId     审批链 ID（便于审计与追踪）
 * @param levels      审批链的逐级定义（至少一级，级号需 1..N 连续）
 */
public record SubmitMultiLevelRequest(
        String taskId,
        String actionType,
        String description,
        BigDecimal amount,
        Map<String, Object> params,
        String riskLevel,
        String chainId,
        List<LevelSpec> levels
) {

    /**
     * 单级层级描述——对应领域层 {@code ApprovalLevel} 的扁平前端视图。
     *
     * @param level          级号（从 1 开始，需连续）
     * @param roleName       本级审批角色名
     * @param approvers      本级候选审批人白名单（任一人批准即本级通过）
     * @param timeoutSeconds 本级超时时长（秒，>0）
     */
    public record LevelSpec(
            int level,
            String roleName,
            List<String> approvers,
            long timeoutSeconds
    ) {
    }

    /**
     * 风险等级兜底：未显式指定时，多级会签默认按 HIGH 处理。
     */
    public String riskLevelOrDefault() {
        return (riskLevel == null || riskLevel.isBlank()) ? "HIGH" : riskLevel;
    }

    /**
     * 审批链 ID 兜底：未显式指定时，用 taskId 派生一个。
     */
    public String chainIdOrDefault() {
        return (chainId == null || chainId.isBlank()) ? "chain-" + taskId : chainId;
    }
}