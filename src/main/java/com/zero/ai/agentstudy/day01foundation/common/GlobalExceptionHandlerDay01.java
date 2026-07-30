package com.zero.ai.agentstudy.day01foundation.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（仅拦截 day01foundation 包下的 Controller）
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.zero.ai.agentstudy.day01foundation.controller")
public class GlobalExceptionHandlerDay01 {

    /** 参数校验异常（@NotBlank 等触发） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("参数校验失败");
        return Result.error(400, msg);
    }

    /** 兜底：其他所有异常（含 AI 调用失败） */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[GlobalException] 未处理异常", e);
        return Result.error(500, e.getMessage());
    }
}