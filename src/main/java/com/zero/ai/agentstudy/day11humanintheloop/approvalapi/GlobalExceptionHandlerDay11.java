package com.zero.ai.agentstudy.day11humanintheloop.approvalapi;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 全局异常处理器（Global Exception Handler）——把领域层抛出的「业务异常」翻译成
 * 「合适的 HTTP 状态码 + 结构化错误体」，避免每个 Controller 里重复写 try/catch。
 *
 * <p><b>为什么需要它？</b>Controller 应该保持「薄」——只做 DTO 翻译、委托 Service、投影结果。
 * 如果每个端点都手写 try/catch 把异常转 4xx/5xx，会造成大量重复代码，且极易漏写。
 * Spring 提供 {@link RestControllerAdvice} 机制：一处集中声明，全局生效，所有
 * {@code @RestController} 抛出的异常都会先经过这里。</p>
 *
 * <p><b>本项目的异常语义约定：</b></p>
 * <ul>
 *   <li>{@link IllegalArgumentException}：入参非法 / 资源不存在 → <b>400 Bad Request</b>
 *       （例如「审批请求不存在」「未知反馈类型」）。</li>
 *   <li>{@link IllegalStateException}：状态不允许该操作 → <b>409 Conflict</b>
 *       （例如「审批人无权批当前级」「状态机不允许该转移」——冲突而非入参错误）。</li>
 *   <li>其它未预期异常 → <b>500 Internal Server Error</b>（兜底，避免把堆栈直接暴露给前端）。</li>
 * </ul>
 *
 * <p><b>错误体格式</b>统一为 {@code {timestamp, status, error, message}}，方便前端统一解析、
 * 统一弹窗提示。生产环境可进一步接入链路追踪 ID（traceId）便于排障。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandlerDay11 {

    /**
     * 入参非法 / 资源不存在 → 400。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 状态冲突（当前状态不允许该操作 / 无权限）→ 409。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage());
    }

    /**
     * 兜底：其它一切未预期异常 → 500。不把原始堆栈抛给前端，只回一句通用提示。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "服务器内部错误：" + ex.getClass().getSimpleName());
    }

    /**
     * 组装统一错误体。
     */
    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }
}