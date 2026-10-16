package com.zero.ai.agentstudy.day14wechatagent.wechatapi.web;

import com.zero.ai.agentstudy.day14wechatagent.wechatapi.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（入站适配器横切关注点）。
 *
 * <p>企业级 API 绝不能把裸异常栈抛给调用方——既不安全（暴露内部结构），
 * 也不友好（调用方无法解析）。本类用 {@code @RestControllerAdvice} 统一拦截
 * 所有 Controller 抛出的异常，转换成标准 {@link ApiResponse} 结构。</p>
 *
 * <p>处理策略分三类：</p>
 * <ol>
 *     <li><strong>参数校验失败</strong>（{@link MethodArgumentNotValidException}）：
 *         返回 400 + 字段级错误信息。</li>
 *     <li><strong>业务非法参数</strong>（{@link IllegalArgumentException}）：
 *         返回 400 + 业务提示（如「通知不存在」）。</li>
 *     <li><strong>兜底未知异常</strong>：返回 500 + 通用提示，<strong>不泄露堆栈</strong>，
 *         但服务端日志完整记录，便于排查。</li>
 * </ol>
 *
 * @author ZeroHub AI Agent Platform
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 Bean Validation 参数校验失败。
     *
     * @param ex 校验异常
     * @return 400 响应，携带首个字段错误信息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("[API] 参数校验失败: {}", msg);
        return ApiResponse.fail(ApiResponse.CODE_INVALID_PARAM, msg);
    }

    /**
     * 处理业务非法参数异常。
     *
     * @param ex 非法参数异常
     * @return 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[API] 业务参数错误: {}", ex.getMessage());
        return ApiResponse.fail(ApiResponse.CODE_INVALID_PARAM, ex.getMessage());
    }

    /**
     * 兜底处理未知异常。
     *
     * @param ex 未知异常
     * @return 500 响应，不泄露堆栈细节
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleUnknown(Exception ex) {
        log.error("[API] 未处理异常", ex);
        return ApiResponse.fail(ApiResponse.CODE_BIZ_ERROR, "服务器内部错误，请稍后重试");
    }
}