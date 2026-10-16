package com.zero.ai.agentstudy.day15knowledgeplatform.api;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理器。
 *
 * <p>职责：把领域异常与框架异常，翻译成统一的 {@link KnowledgeResult} 结构，
 * 并映射到合适的 HTTP 状态码。</p>
 *
 * <p><b>为什么要专门写这个类？</b>
 * 因为「异常 → 响应」的映射如果不统一，会出现三种典型乱象：</p>
 * <ol>
 *     <li>有的接口返回 {@code {"code":500,"msg":"..."}}，有的返回纯文本堆栈，
 *         前端要为每个接口写不同的解析逻辑；</li>
 *     <li><b>把内部错误详情直接暴露给客户端</b>——
 *         异常信息里可能含 SQL、文件路径、内部服务名，这是信息泄漏；</li>
 *     <li>业务失败（如"无权访问"）被当成 500 上报监控，
 *         于是告警系统里全是噪音，真正的系统故障反而被淹没了。</li>
 * </ol>
 *
 * <p><b>最关键的一条：错误码要区分"业务失败"与"系统故障"。</b>
 * 两者需要的处理完全不同——业务失败是正常流程，记日志即可；
 * 系统故障才需要告警、需要人工介入。
 * 混为一谈的后果是「告警疲劳」，最终所有告警都无人理会。</p>
 *
 * @author ZeroAi
 */
@RestControllerAdvice(basePackages = "com.zero.ai.agentstudy.day15knowledgeplatform")
public class KnowledgeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeExceptionHandler.class);

    /**
     * 领域异常：业务失败，返回 400 与具体错误码。
     *
     * <p>这里返回 400 而不是 500，因为领域异常代表的是「请求不合法/不被允许」，
     * 是<b>调用方的问题</b>，不是服务端故障。</p>
     */
    @ExceptionHandler(KnowledgeException.class)
    public ResponseEntity<KnowledgeResult<Void>> handleKnowledgeException(KnowledgeException ex) {
        // 业务失败：记录即可，不需要告警
        log.warn("业务失败 [{}] {}", ex.getErrorCode().code(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(KnowledgeResult.fail(ex));
    }

    /**
     * 参数校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<KnowledgeResult<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(KnowledgeResult.fail("VALIDATION_FAILED", message));
    }

    /**
     * 未预期的系统异常：返回 500，且<b>不向客户端暴露内部细节</b>。
     *
     * <p>注意这里只返回固定文案，异常堆栈只进日志。
     * 把 {@code ex.getMessage()} 直接返回给客户端是常见的错误做法——
     * 它可能包含数据库表名、文件路径、内部 IP，属于典型的<b>信息泄漏</b>。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<KnowledgeResult<Void>> handleUnexpected(Exception ex) {
        // 系统故障：ERROR 级别 + 完整堆栈，触发告警
        log.error("系统异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(KnowledgeResult.fail("INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
    }
}
