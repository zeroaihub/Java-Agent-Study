package com.zero.ai.agentstudy.day16knowledgeagentv2.core.common;

/**
 * Knowledge Agent V2 统一错误码。
 *
 * <p>错误码采用「模块前缀 + 序号」，便于快速定位问题归属模块：</p>
 * <ul>
 *     <li>KA-0xxx：通用 / 核心</li>
 *     <li>KA-1xxx：Agent Router / Knowledge Router（路由）</li>
 *     <li>KA-2xxx：Query（改写 / 分解 / 多查询）</li>
 *     <li>KA-3xxx：Retrieval（混合检索）</li>
 *     <li>KA-4xxx：Reranker（重排序）</li>
 *     <li>KA-5xxx：ACL（权限感知）</li>
 *     <li>KA-6xxx：Citation / Grounding / Verifier（引用 / 接地 / 验证）</li>
 *     <li>KA-7xxx：Knowledge Gap / Version / Update（知识治理）</li>
 *     <li>KA-8xxx：Feedback / Evaluation（反馈 / 评估）</li>
 * </ul>
 *
 * @author ZeroAi
 */
public enum AgentErrorCode {

    // ---------- 通用 ----------
    INTERNAL_ERROR("KA-0001", "系统内部错误"),
    INVALID_ARGUMENT("KA-0002", "参数非法"),
    RESOURCE_NOT_FOUND("KA-0003", "资源不存在"),
    OPERATION_NOT_ALLOWED("KA-0004", "操作不被允许"),
    TENANT_REQUIRED("KA-0005", "缺少租户上下文"),
    TENANT_MISMATCH("KA-0006", "租户不匹配，禁止跨租户访问"),

    // ---------- 路由 ----------
    AGENT_NOT_FOUND("KA-1001", "未找到可处理该请求的 Agent"),
    KNOWLEDGE_BASE_NOT_ROUTED("KA-1002", "无法为该问题路由到合适的知识库"),
    ROUTER_LOW_CONFIDENCE("KA-1003", "路由置信度过低"),

    // ---------- Query ----------
    QUERY_EMPTY("KA-2001", "查询内容为空"),
    QUERY_REWRITE_FAILED("KA-2002", "查询改写失败"),
    QUERY_DECOMPOSE_FAILED("KA-2003", "查询分解失败"),

    // ---------- 检索 ----------
    RETRIEVAL_FAILED("KA-3001", "检索失败"),
    VECTOR_SEARCH_FAILED("KA-3002", "向量检索失败"),
    KEYWORD_SEARCH_FAILED("KA-3003", "关键词检索失败"),
    FUSION_FAILED("KA-3004", "结果融合失败"),

    // ---------- 重排 ----------
    RERANK_FAILED("KA-4001", "重排序失败"),

    // ---------- 权限 ----------
    ACCESS_DENIED("KA-5001", "无权访问该资源"),
    ACL_EVALUATION_FAILED("KA-5002", "权限判定失败"),

    // ---------- 引用 / 接地 / 验证 ----------
    CITATION_MISSING("KA-6001", "答案缺少引用来源"),
    ANSWER_UNGROUNDED("KA-6002", "答案缺乏检索依据"),
    VERIFICATION_FAILED("KA-6003", "答案验证失败"),

    // ---------- 知识治理 ----------
    KNOWLEDGE_GAP_DETECTED("KA-7001", "检测到知识缺口"),
    KNOWLEDGE_VERSION_CONFLICT("KA-7002", "知识版本冲突"),
    KNOWLEDGE_UPDATE_FAILED("KA-7003", "知识更新失败"),

    // ---------- 反馈 / 评估 ----------
    FEEDBACK_FAILED("KA-8001", "反馈记录失败"),
    EVALUATION_FAILED("KA-8002", "评估执行失败");

    private final String code;
    private final String defaultMessage;

    AgentErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}