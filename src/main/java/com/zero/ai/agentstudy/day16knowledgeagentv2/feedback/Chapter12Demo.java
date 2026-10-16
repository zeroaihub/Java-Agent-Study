package com.zero.ai.agentstudy.day16knowledgeagentv2.feedback;

/**
 * Chapter 12 端到端演示：反馈闭环的收集 → 归因。
 *
 * <p>同样是「点踩」，归因器结合链路快照给出完全不同的根因与优化建议——
 * 这就是结构化痕迹 + 规则归因的威力：让「答案不好」从模糊抱怨变成可执行的工单。</p>
 *
 * @author ZeroAi
 */
public class Chapter12Demo {

    public static void main(String[] args) {
        var attributor = new RuleBasedFeedbackAttributor(0.5);
        String q = "2026 年差旅报销标准是多少？";

        // 场景一：检索最高分只有 0.3 ⇒ 检索未命中
        demo("检索弱", attributor,
                Feedback.of("t1", q, FeedbackType.THUMBS_DOWN, ""),
                new AnswerTrace("t1", 0.30, 0, false, false));

        // 场景二：检索分高但发生权限过滤且无引用 ⇒ 权限拦截
        demo("权限挡", attributor,
                Feedback.of("t2", q, FeedbackType.THUMBS_DOWN, ""),
                new AnswerTrace("t2", 0.80, 0, false, true));

        // 场景三：检索分高、无引用、无过滤 ⇒ 排序问题
        demo("排序偏", attributor,
                Feedback.of("t3", q, FeedbackType.THUMBS_DOWN, ""),
                new AnswerTrace("t3", 0.85, 0, false, false));

        // 场景四：用户纠错「应为 800」，有引用但生成写错 ⇒ 生成错误
        demo("纠错", attributor,
                Feedback.of("t4", q, FeedbackType.CORRECTION, "应为每天 800 元"),
                new AnswerTrace("t4", 0.90, 2, true, false));

        // 场景五：点赞 ⇒ 无需归因
        demo("点赞", attributor,
                Feedback.of("t5", q, FeedbackType.THUMBS_UP, ""),
                new AnswerTrace("t5", 0.90, 2, true, false));
    }

    private static void demo(String scene, RuleBasedFeedbackAttributor attr,
                             Feedback fb, AnswerTrace trace) {
        AttributionResult r = attr.attribute(fb, trace);
        System.out.printf("[%s] 反馈=%s 归因=%s | 原因=%s | 建议=%s%n",
                scene, fb.type(), r, r.reason(), r.suggestedAction());
    }
}