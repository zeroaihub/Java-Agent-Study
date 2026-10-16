package com.zero.ai.agentstudy.day16knowledgeagentv2.observability;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;

import java.util.Set;

/**
 * Chapter 14 演示：链路可观测性。
 *
 * <p>模拟一次问答流水线，用 TraceCollector 逐阶段计时，最后打印分阶段耗时账单。</p>
 *
 * @author ZeroAi
 */
public class Chapter14Demo {

    public static void main(String[] args) {
        AgentContext ctx = AgentContext.of(
                TenantId.of("acme"),
                WorkspaceId.of("ws-1"),
                UserId.of("u-1001"),
                Set.of("EMPLOYEE"));

        TraceCollector trace = new TraceCollector(ctx);

        String route = trace.measure("route", () -> {
            sleep(5);
            return "hr-kb";
        });
        var chunks = trace.measure("retrieve", () -> {
            sleep(40);
            return java.util.List.of("chunk-1", "chunk-2");
        });
        trace.measure("rerank", () -> sleep(15));
        trace.measure("grounding", () -> sleep(10));
        trace.measure("generate", () -> sleep(120));

        System.out.println("路由到知识库: " + route + "，命中片段数: " + chunks.size());
        System.out.println(trace.report());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}