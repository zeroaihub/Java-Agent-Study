package com.zero.ai.agentstudy.day01foundation.common;

import lombok.Data;

/**
 * 统一 API 返回结果包装
 *
 * @param <T> 数据类型
 */
@Data
public class Result<T> {

    /** 业务状态码：200 成功，非 200 失败 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    /** 成功（带数据） */
    public static <T> Result<T> success(T data) {
        Result<T> r = new Result<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /** 失败（带错误信息） */
    public static <T> Result<T> error(int code, String message) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(null);
        return r;
    }
}