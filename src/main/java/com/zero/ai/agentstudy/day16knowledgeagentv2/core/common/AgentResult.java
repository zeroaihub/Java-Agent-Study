package com.zero.ai.agentstudy.day16knowledgeagentv2.core.common;

import java.io.Serializable;
import java.time.Instant;

/**
 * 统一 API 返回结果封装（企业级）。
 *
 * <p>所有对外接口统一返回该结构，携带 traceId 便于全链路追踪。
 * 采用不可变 record + 静态工厂，天然线程安全。</p>
 *
 * @param <T> 业务数据类型
 * @author ZeroAi
 */
public record AgentResult<T>(
        boolean success,
        String code,
        String message,
        T data,
        String traceId,
        Instant timestamp
) implements Serializable {

    public static final String CODE_OK = "OK";

    /** 成功（无数据）。 */
    public static <T> AgentResult<T> ok() {
        return ok(null);
    }

    /** 成功（带数据）。 */
    public static <T> AgentResult<T> ok(T data) {
        return new AgentResult<>(true, CODE_OK, "success", data, currentTraceId(), Instant.now());
    }

    /** 失败（自定义码）。 */
    public static <T> AgentResult<T> fail(String code, String message) {
        return new AgentResult<>(false, code, message, null, currentTraceId(), Instant.now());
    }

    /** 失败（来自领域异常）。 */
    public static <T> AgentResult<T> fail(AgentException ex) {
        return fail(ex.getErrorCode().code(), ex.getMessage());
    }

    /**
     * 读取当前请求 traceId（接入 OpenTelemetry / MDC 后可从中取值）。
     */
    private static String currentTraceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId != null ? traceId : "-";
    }
}