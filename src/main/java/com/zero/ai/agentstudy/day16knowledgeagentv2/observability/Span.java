package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

/**
 * 一段链路计时记录（Chapter 14）。
 *
 * <p>企业 Agent 的每一次问答都是多阶段流水线（路由→检索→重排→接地→生成）。
 * 出问题时必须能回答「慢在哪一步」，Span 就是把每个阶段的耗时和归属 traceId 记录下来，
 * 供上层聚合成一条完整链路。生产环境对接 OpenTelemetry，此处是等价的最小内核。</p>
 *
 * @param traceId 归属的请求链路 ID
 * @param stage   阶段名（如 retrieve / rerank / grounding）
 * @param costMs  耗时（毫秒）
 * @param success 该阶段是否成功
 * @author ZeroAi
 */
public record Span(String traceId, String stage, long costMs, boolean success) {

    public Span {
        AgentException.requireText(traceId, "traceId");
        AgentException.requireText(stage, "stage");
    }

    @Override
    public String toString() {
        return String.format("[%s] %-10s %4dms %s",
                traceId, stage, costMs, success ? "OK" : "FAIL");
    }
}