package com.zero.ai.agentstudy.day17codeagent.core.common;

import java.io.Serializable;

/**
 * Code Agent 统一结构化返回。
 *
 * <p>Code Agent 的一次任务可能失败在任意环节（读文件、打补丁、编译、测试），
 * 如果每层都抛异常给前端，前端就只能显示「500 Internal Server Error」，无从下手。
 * 所以统一用 {@code CodeResult} 承载：<b>成功与否 + 数据 + 错误码 + 可执行的建议</b>。</p>
 *
 * <p>{@code hint} 字段是本节刻意加入的：Code Agent 的失败大多数是「用户可修正」的
 * （补丁上下文不匹配、编译报错、测试不过），直接把可执行的修复建议带回去，
 * 比单纯一个错误消息有用得多。</p>
 *
 * @param success 是否成功
 * @param data    成功时的数据；失败时为 null
 * @param code    错误码，成功时为 null
 * @param message 面向人的描述
 * @param hint    可执行的修复建议（可为空）
 * @author ZeroAi
 */
public record CodeResult<T>(
        boolean success,
        T data,
        String code,
        String message,
        String hint
) implements Serializable {

    // ---------- 成功 ----------

    public static <T> CodeResult<T> success(T data) {
        return new CodeResult<>(true, data, null, "OK", null);
    }

    public static <T> CodeResult<T> success(T data, String message) {
        return new CodeResult<>(true, data, null, message, null);
    }

    // ---------- 失败 ----------

    public static <T> CodeResult<T> failure(CodeErrorCode code, String message) {
        return new CodeResult<>(false, null, code.code(), message, null);
    }

    public static <T> CodeResult<T> failure(CodeErrorCode code, String message, String hint) {
        return new CodeResult<>(false, null, code.code(), message, hint);
    }

    /** 把异常翻译成结构化失败。 */
    public static <T> CodeResult<T> failure(CodeException e) {
        return new CodeResult<>(false, null, e.code(), e.getMessage(), null);
    }

    // ---------- 便捷判断 ----------

    public boolean failed() {
        return !success;
    }

    /** 失败时是否附带了修复建议。 */
    public boolean hasHint() {
        return hint != null && !hint.isBlank();
    }

    @Override
    public String toString() {
        if (success) {
            return "CodeResult[success, message=" + message + "]";
        }
        return "CodeResult[failure, code=" + code + ", message=" + message
                + (hasHint() ? ", hint=" + hint : "") + "]";
    }
}
