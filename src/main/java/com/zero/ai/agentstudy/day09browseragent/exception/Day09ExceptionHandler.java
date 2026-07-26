package com.zero.ai.agentstudy.day09browseragent.exception;

import com.example.agentstudy.day09browseragent.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Day09 专属异常处理器。
 *
 * <p><b>隔离约束</b>：通过 {@code basePackages} 限定只拦截 Day09 controller 包，
 * 不污染其它模块的异常处理逻辑（沿用 Day02 起的隔离策略）。</p>
 *
 * @author AI架构师
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.example.agentstudy.day09browseragent.controller")
public class Day09ExceptionHandler {

    /** 400：参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("[Day09][参数校验失败] {}", msg);
        return R.fail(400, msg);
    }

    /** 400：非法参数。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[Day09][非法参数] {}", e.getMessage());
        return R.fail(400, e.getMessage());
    }

    /** 503：浏览器池繁忙 / 会话获取超时。 */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public R<Void> handleBusy(IllegalStateException e) {
        log.warn("[Day09][浏览器繁忙] {}", e.getMessage());
        return R.fail(503, e.getMessage());
    }

    /** 500：兜底（含浏览器操作失败、超时等）。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleUnknown(Exception e) {
        log.error("[Day09][浏览器操作异常] {}", e.getMessage(), e);
        return R.fail(500, "浏览器操作失败: " + e.getMessage());
    }
}