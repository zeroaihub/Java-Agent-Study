package com.zero.ai.agentstudy.day16knowledgeagentv2.acl;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.UserId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.WorkspaceId;
import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.RetrievedChunk;

import java.util.List;
import java.util.Set;

/**
 * Chapter 07 演示：权限感知检索（ACL 过滤）。
 *
 * <p>同一批检索结果，普通员工与 HR 看到的内容不同——薪酬片段仅 HR 可见。</p>
 *
 * @author ZeroAi
 */
public class Chapter07Demo {

    public static void main(String[] args) {
        List<RetrievedChunk> retrieved = List.of(
                new RetrievedChunk("c1", "d1", "员工手册", "年假每年 10 天", 0.9, Set.of("EMPLOYEE")),
                new RetrievedChunk("c2", "d3", "薪酬制度", "P7 薪资带宽为 40-60k", 0.85, Set.of("HR")),
                new RetrievedChunk("c3", "d4", "公司简介", "公司成立于 2015 年", 0.6, Set.of())
        );

        AccessPolicy policy = new AccessPolicy();

        AgentContext employee = AgentContext.of(
                TenantId.of("t1"), WorkspaceId.of("ws1"), UserId.of("alice"), Set.of("EMPLOYEE"));
        AgentContext hr = AgentContext.of(
                TenantId.of("t1"), WorkspaceId.of("ws1"), UserId.of("bob"), Set.of("EMPLOYEE", "HR"));

        System.out.println("=== 普通员工可见 ===");
        policy.filter(employee, retrieved).forEach(Chapter07Demo::print);
        System.out.println("被拒绝片段数: " + policy.deniedCount(employee, retrieved));

        System.out.println("\n=== HR 可见 ===");
        policy.filter(hr, retrieved).forEach(Chapter07Demo::print);
        System.out.println("被拒绝片段数: " + policy.deniedCount(hr, retrieved));
    }

    private static void print(RetrievedChunk c) {
        System.out.printf("  %s | %s | acl=%s%n", c.chunkId(), c.content(), c.aclLabels());
    }
}