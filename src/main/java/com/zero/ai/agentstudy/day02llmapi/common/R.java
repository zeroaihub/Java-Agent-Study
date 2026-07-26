package com.zero.ai.agentstudy.day02llmapi.common;

import lombok.Data;

/**
 * Day02 模块统一返回结果包装。
 * <p>
 * 独立于 Day1 的 {@code Result}，结构一致（code / message / data），
 * 避免跨模块耦合，保证 Day2 可独立演进。
 *
 * @param <T> 业务数据类型
 */
@Data
public class R<T> {

    /** 业务状态码：200 成功，非 200 失败 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 成功（带数据） */
    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /** 失败（带错误码与信息） */
    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(null);
        return r;
    }
}