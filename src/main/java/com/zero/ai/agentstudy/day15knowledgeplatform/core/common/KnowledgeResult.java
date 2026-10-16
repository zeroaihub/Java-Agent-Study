package com.zero.ai.agentstudy.day15knowledgeplatform.core.common;

import java.io.Serializable;
import java.time.Instant;

/**
 * 统一 API 返回结果封装（企业级）。
 *
 * <p>所有对外接口统一返回该结构，便于前端/调用方统一处理。
 * 采用不可变记录（record）+ 静态工厂，避免可变状态带来的并发问题。</p>
 *
 * @param <T> 业务数据类型
 * @author ZeroAi
 */
public record KnowledgeResult<T>(
        boolean success,
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) implements Serializable {

    /** 成功码。 */
    public static final String CODE_OK = "OK";

    /**
     * 构建成功结果（无数据）。
     */
    public static <T> KnowledgeResult<T> ok() {
        return ok(null);
    }

    /**
     * 构建成功结果。
     *
     * @param data 业务数据
     */
    public static <T> KnowledgeResult<T> ok(T data) {
        return new KnowledgeResult<>(true, CODE_OK, "success", data, currentTraceId(), Instant.now());
    }

    /**
     * 构建失败结果。
     *
     * @param code    业务错误码
     * @param message 错误消息
     */
    public static <T> KnowledgeResult<T> fail(String code, String message) {
        return new KnowledgeResult<>(false, code, message, null, currentTraceId(), Instant.now());
    }

    /**
     * 从领域异常构建失败结果。
     */
    public static <T> KnowledgeResult<T> fail(KnowledgeException ex) {
        return fail(ex.getErrorCode().code(), ex.getMessage());
    }

    /**
     * 取当前请求的 traceId（若接入 OpenTelemetry / MDC，可在此读取）。
     * 这里做最小实现：从 MDC 读取，无则返回占位。
     */
    private static String currentTraceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId != null ? traceId : "-";
    }
}