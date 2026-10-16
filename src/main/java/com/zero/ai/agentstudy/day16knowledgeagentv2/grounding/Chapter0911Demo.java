package com.zero.ai.agentstudy.day16knowledgeagentv2.grounding;

import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.RetrievedChunk;

import java.util.List;
import java.util.Set;

/**
 * Chapter 09~11 演示：引用 + 接地 + 验证。
 *
 * <p>三个场景：有依据的答案（接地成功、带引用）、幻觉答案（无依据、拒答）、
 * 无检索结果（知识缺口、拒答）。</p>
 *
 * @author ZeroAi
 */
public class Chapter0911Demo {

    public static void main(String[] args) {
        List<RetrievedChunk> context = List.of(
                new RetrievedChunk("c1", "d1", "员工手册", "公司年假政策：每年享有 10 天带薪年假", 0.9, Set.of()),
                new RetrievedChunk("c2", "d1", "员工手册", "试用期员工年假按比例折算", 0.7, Set.of())
        );

        GroundingVerifier verifier = new GroundingVerifier();

        System.out.println("=== 场景1：有依据的答案 ===");
        GroundedAnswer a1 = verifier.verify("公司每年享有 10 天带薪年假", context);
        print(a1);

        System.out.println("\n=== 场景2：幻觉答案（无依据）===");
        GroundedAnswer a2 = verifier.verify("公司提供无限期带薪休假和免费环球旅行", context);
        print(a2);

        System.out.println("\n=== 场景3：无检索结果（知识缺口）===");
        GroundedAnswer a3 = verifier.verify("公司股票代码是多少", List.of());
        print(a3);
    }

    private static void print(GroundedAnswer a) {
        System.out.println("  答案: " + a.answer());
        System.out.println("  接地: " + a.grounded() + " | 置信度: " + String.format("%.2f", a.confidence())
                + " | 理由: " + a.reason());
        if (!a.citations().isEmpty()) {
            System.out.println("  引用:");
            a.citations().forEach(c -> System.out.println("    " + c));
        }
    }
}