package com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 提交审批的请求 DTO（Data Transfer Object）——API 层与领域层之间的「防腐层」。
 *
 * <p>为什么不直接把 {@code AgentAction} 暴露给前端？三点原因：</p>
 * <ul>
 *   <li><b>解耦：</b>领域模型的字段命名、结构是内部实现细节，一旦前端直接依赖它，
 *       领域模型稍一重构就会破坏 API 契约。DTO 是稳定的对外契约，可以独立演进。</li>
 *   <li><b>安全：</b>领域对象可能带有内部方法、审计字段，不该全部裸露给外部。
 *       DTO 只暴露「前端确实需要填的字段」。</li>
 *   <li><b>校验：</b>DTO 是承接 HTTP 入参的第一道关，天然是做参数校验的位置。</li>
 * </ul>
 *
 * <p>本 DTO 承载「一个 Agent 想执行的动作」，Controller 会把它翻译成领域层的
 * {@code AgentAction} 再交给审批引擎。</p>
 *
 * <p>使用 record 是因为 DTO 天然是「一组不可变的传输字段」，无需可变状态，
 * record 自动生成构造器、访问器、equals/hashCode，最贴合 DTO 语义。</p>
 *
 * @param taskId      任务 ID（关联到某次 Agent 执行，也是后续反馈的关联键）
 * @param actionType  动作类型（如 DELETE_ORDER / TRANSFER / SEND_EMAIL），用于风险评估与反馈归类
 * @param description 动作的人类可读描述（展示给审批人看）
 * @param amount      涉及金额（可空；转账、退款类动作用得上，用于风险分级）
 * @param params      动作参数（可空；如 {"orderIds": [...], "reason": "..."}）
 */
public record SubmitApprovalRequest(
        String taskId,
        String actionType,
        String description,
        BigDecimal amount,
        Map<String, Object> params
) {
}