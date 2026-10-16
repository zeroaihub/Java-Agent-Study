package com.zero.ai.agentstudy.day16knowledgeagentv2.feedback;

/**
 * 归因结论：把「答案不好」定位到推理链的具体环节（Chapter 12）。
 *
 * <p>归因靠前面每一章埋下的结构化痕迹：traceId 串起全链路，Citation 记录用了哪些片段，
 * VerificationReport 记录验证结论。有了这些，归因才不是拍脑袋，而是有据可查。</p>
 *
 * @author ZeroAi
 */
public enum AttributionResult {

    /** 检索环节问题：没找到该找到的片段。 */
    RETRIEVAL_MISS("检索未命中相关内容", "优化切片 / 补充文档"),
    /** 排序环节问题：对的片段排得太靠后。 */
    RANKING_ERROR("相关片段排序过低", "调整重排权重"),
    /** 权限环节问题：该看的被 ACL 挡了。 */
    ACCESS_ERROR("相关内容被权限拦截", "复核 ACL 标签配置"),
    /** 生成环节问题：片段正确但答案写错。 */
    GENERATION_ERROR("片段正确但答案有误", "优化 Prompt / 加强验证"),
    /** 无法定位或正向反馈。 */
    NONE("无需归因", "无");

    private final String reason;
    private final String suggestedAction;

    AttributionResult(String reason, String suggestedAction) {
        this.reason = reason;
        this.suggestedAction = suggestedAction;
    }

    public String reason() {
        return reason;
    }

    public String suggestedAction() {
        return suggestedAction;
    }
}