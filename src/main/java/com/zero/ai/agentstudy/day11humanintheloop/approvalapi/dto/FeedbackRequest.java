package com.zero.ai.agentstudy.day11humanintheloop.approvalapi.dto;

/**
 * 反馈提交请求 DTO——承载审批人（或用户）对某次 Agent 产出的一条反馈。
 *
 * <p>对应 Chapter 07 的四种反馈类型：APPROVE_RATING / REJECT_RATING /
 * CORRECTION / SUGGESTION。前端只需填「反馈类型 + 必要内容」，Controller
 * 会路由到 {@code FeedbackEngine} 的对应语义化方法。</p>
 *
 * <p>各字段在不同反馈类型下的语义：</p>
 * <ul>
 *   <li><b>APPROVE（点赞）：</b>可选 {@code score}（1~5），{@code content} 忽略。</li>
 *   <li><b>REJECT（点踩）：</b>{@code content} 作为否定原因。</li>
 *   <li><b>CORRECTION（纠正）：</b>{@code content} 必填，作为「期望的正确产出」。</li>
 *   <li><b>SUGGESTION（建议）：</b>{@code content} 作为改进方向。</li>
 * </ul>
 *
 * @param taskId       被反馈的任务 ID（关联到某次 Agent 执行）
 * @param targetOutput 被反馈的那次产出内容（原文）
 * @param feedbackType 反馈类型：APPROVE / REJECT / CORRECTION / SUGGESTION
 * @param content      反馈内容（否定原因 / 正确产出 / 改进建议，含义随类型而变）
 * @param score        评分（1~5，仅点赞可选带）
 * @param reviewer     反馈人
 */
public record FeedbackRequest(
        String taskId,
        String targetOutput,
        String feedbackType,
        String content,
        Integer score,
        String reviewer
) {

    /** reviewer 为空时给占位，避免反馈记录里出现 null 审阅人。 */
    public String reviewerOrAnonymous() {
        return (reviewer == null || reviewer.isBlank()) ? "anonymous" : reviewer;
    }
}