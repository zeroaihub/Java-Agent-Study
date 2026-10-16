package com.zero.ai.agentstudy.day16knowledgeagentv2.feedback;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

/**
 * 一次回答的链路快照（简化版），供反馈归因使用（Chapter 12）。
 *
 * <p>归因需要「当次回答用了哪些片段、验证结论如何」。这些痕迹在生成答案时留存，
 * 归因时按 traceId 取回。这里用扁平字段简化演示，生产中通常从 Observability
 * 的 Span 链路 + Citation 列表还原。</p>
 *
 * @param traceId       链路 ID
 * @param topScore      当次检索最高相关度（归一到 [0,1]）
 * @param citationCount 答案引用的片段数
 * @param verified      答案是否通过验证（Ch10）
 * @param aclFiltered   是否发生过 ACL 过滤（有内容被权限挡掉）
 * @author ZeroAi
 */
public record AnswerTrace(
        String traceId,
        double topScore,
        int citationCount,
        boolean verified,
        boolean aclFiltered
) {
    public AnswerTrace {
        AgentException.requireText(traceId, "traceId");
    }
}