package com.zero.ai.agentstudy.day14wechatagent.wechatapi.dto;

import java.time.Instant;

/**
 * 统一 API 响应包装（入站适配器 DTO）。
 *
 * <p>企业级 REST API 的首要约定：<strong>所有接口返回同一种外层结构</strong>，
 * 前端/调用方只需写一套解析逻辑。本类用 record 实现不可变响应体，包含：</p>
 * <ul>
 *     <li>{@code code}：业务状态码（0 表示成功，非 0 表示各类业务错误）。</li>
 *     <li>{@code message}：人类可读的提示信息。</li>
 *     <li>{@code data}：业务数据载荷（成功时非空，失败时通常为 null）。</li>
 *     <li>{@code timestamp}：服务端响应时间戳（便于排查、对账）。</li>
 * </ul>
 *
 * <p><strong>为什么业务码独立于 HTTP 状态码？</strong>HTTP 状态码表达
 * 「传输层/协议层」结果（200/404/500），业务码表达「业务语义」结果
 * （如「租户额度不足」「通知已存在」）。二者分离，调用方能精准区分
 * 「请求没打通」和「请求通了但业务拒绝」。</p>
 *
 * @param <T>       数据载荷类型
 * @param code      业务状态码，0 为成功
 * @param message   提示信息
 * @param data      业务数据
 * @param timestamp 响应时间戳
 * @author ZeroHub AI Agent Platform
 */
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

    /** 成功业务码。 */
    public static final int CODE_SUCCESS = 0;

    /** 通用业务错误码。 */
    public static final int CODE_BIZ_ERROR = 1;

    /** 参数校验错误码。 */
    public static final int CODE_INVALID_PARAM = 400;

    /**
     * 构造成功响应（带数据）。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_SUCCESS, "success", data, Instant.now());
    }

    /**
     * 构造成功响应（无数据）。
     *
     * @return 成功响应
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(CODE_SUCCESS, "success", null, Instant.now());
    }

    /**
     * 构造业务失败响应。
     *
     * @param code    业务错误码
     * @param message 错误信息
     * @param <T>     数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, Instant.now());
    }
}