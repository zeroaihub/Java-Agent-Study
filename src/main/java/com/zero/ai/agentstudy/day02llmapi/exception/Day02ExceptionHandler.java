package com.zero.ai.agentstudy.day02llmapi.exception;

import com.zero.ai.agentstudy.day02llmapi.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Day02 专属异常处理器。
 * <p>
 * 关键：通过 basePackages 限定只拦截 Day02 controller 包，
 * 避免污染 Day01 及其他模块的全局异常处理逻辑。
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.zero.ai.agentstudy.day02llmapi.controller")
public class Day02ExceptionHandler {

    /** 400：参数校验失败（@Valid 触发）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("[Day02][参数校验失败] {}", msg);
        return R.fail(400, "参数校验失败：" + msg);
    }

    /** 400：业务参数非法（如多轮会话缺 conversationId）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[Day02][非法参数] {}", e.getMessage());
        return R.fail(400, e.getMessage());
    }

    /** 429：触发限流。 */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public R<Void> handleRateLimit(IllegalStateException e) {
        log.warn("[Day02][限流] {}", e.getMessage());
        return R.fail(429, e.getMessage());
    }

    /** 502：上游大模型调用失败（降级）。 */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public R<Void> handleUpstream(RuntimeException e) {
        log.error("[Day02][上游调用失败] {}", e.getMessage(), e);
        return R.fail(502, "AI 服务暂时不可用，请稍后重试");
    }

    /** 500：兜底。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleUnknown(Exception e) {
        log.error("[Day02][未知异常] {}", e.getMessage(), e);
        return R.fail(500, "服务器内部错误");
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + " " + fe.getDefaultMessage();
    }
}