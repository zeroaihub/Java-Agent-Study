package com.zero.ai.agentstudy.day15knowledgeplatform.core.common;

/**
 * 知识平台统一错误码。
 *
 * <p>错误码采用「模块前缀 + 序号」的方式，便于快速定位问题归属模块：</p>
 * <ul>
 *     <li>KP-0xxx：通用/核心</li>
 *     <li>KP-1xxx：文档域</li>
 *     <li>KP-2xxx：解析</li>
 *     <li>KP-3xxx：切分</li>
 *     <li>KP-4xxx：向量化</li>
 *     <li>KP-5xxx：检索/重排</li>
 *     <li>KP-6xxx：工作空间/权限</li>
 *     <li>KP-7xxx：同步/采集</li>
 * </ul>
 *
 * @author ZeroAi
 */
public enum KnowledgeErrorCode {

    // ---------- 通用 ----------
    INTERNAL_ERROR("KP-0001", "系统内部错误"),
    INVALID_ARGUMENT("KP-0002", "参数非法"),
    RESOURCE_NOT_FOUND("KP-0003", "资源不存在"),
    OPERATION_NOT_ALLOWED("KP-0004", "操作不被允许"),

    // ---------- 租户 / 权限 ----------
    TENANT_REQUIRED("KP-6001", "缺少租户上下文"),
    TENANT_MISMATCH("KP-6002", "租户不匹配，禁止跨租户访问"),
    ACCESS_DENIED("KP-6003", "无权访问该资源"),
    WORKSPACE_NOT_FOUND("KP-6004", "工作空间不存在"),
    KNOWLEDGE_BASE_NOT_FOUND("KP-6005", "知识库不存在"),

    // ---------- 文档 ----------
    DOCUMENT_NOT_FOUND("KP-1001", "文档不存在"),
    DOCUMENT_STATE_ILLEGAL("KP-1002", "文档状态非法，无法执行该操作"),
    DOCUMENT_TOO_LARGE("KP-1003", "文档超出大小限制"),
    UNSUPPORTED_FILE_TYPE("KP-1004", "不支持的文件类型"),

    // ---------- 解析 / 切分 / 向量化 ----------
    PARSE_FAILED("KP-2001", "文档解析失败"),
    OCR_FAILED("KP-2002", "图片 OCR 失败"),
    CHUNK_FAILED("KP-3001", "文档切分失败"),
    EMBEDDING_FAILED("KP-4001", "向量化失败"),

    // ---------- 检索 ----------
    RETRIEVAL_FAILED("KP-5001", "检索失败"),
    RERANK_FAILED("KP-5002", "重排序失败"),

    // ---------- 同步 ----------
    SYNC_FAILED("KP-7001", "同步失败"),
    CRAWL_FAILED("KP-7002", "网页采集失败");

    private final String code;
    private final String defaultMessage;

    KnowledgeErrorCode(String code, String defaultMessage) {
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