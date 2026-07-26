package com.zero.ai.agentstudy.day09browseragent.common;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * R —— Day09 模块统一返回体（沿用前序 Day 风格，本模块独立一份，避免跨模块耦合）。
 *
 * @param <T> 数据类型
 * @author AI架构师
 */
@Data
@AllArgsConstructor
public class R<T> {

    /** 业务状态码，0 表示成功 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    public static <T> R<T> ok(T data) {
        return new R<>(0, "success", data);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }
}