package com.zero.ai.agentstudy.day17codeagent.core.common;

/**
 * Code Agent 统一业务异常。
 *
 * <p>与 Day16 保持一致的三条铁律：</p>
 * <ol>
 *     <li><b>只抛业务异常</b>：工具失败、沙箱超时这类「可预期的失败」应当是返回值，而不是异常；</li>
 *     <li><b>异常必须携带错误码</b>：便于网关统一翻译成 HTTP 状态与前端提示；</li>
 *     <li><b>参数校验 fail-fast</b>：入口就把非法输入挡住，不要让脏数据流入 links 深层。</li>
 * </ol>
 *
 * <p>这里额外提供了静态断言方法，让「入口校验」读起来是一句话而不是三行 if。</p>
 *
 * @author ZeroAi
 */
public class CodeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CodeErrorCode errorCode;

    public CodeException(CodeErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CodeException(CodeErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public CodeErrorCode errorCode() {
        return errorCode;
    }

    public String code() {
        return errorCode.code();
    }

    // ---------- 静态工厂 ----------

    public static CodeException of(CodeErrorCode code, String message) {
        return new CodeException(code, message);
    }

    public static CodeException internal(String message, Throwable cause) {
        return new CodeException(CodeErrorCode.INTERNAL_ERROR, message, cause);
    }

    // ---------- 断言工具 ----------

    /** 非空断言。 */
    public static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new CodeException(CodeErrorCode.INVALID_ARGUMENT, name + " 不能为空");
        }
        return value;
    }

    /** 非空白文本断言。 */
    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new CodeException(CodeErrorCode.INVALID_ARGUMENT, name + " 不能为空");
        }
        return value;
    }

    /** 布尔断言。 */
    public static void requireTrue(boolean condition, CodeErrorCode code, String message) {
        if (!condition) {
            throw new CodeException(code, message);
        }
    }
}
