package com.zero.ai.agentstudy.day16knowledgeagentv2.core.common;

/**
 * Knowledge Agent V2 统一领域异常。
 *
 * <p>整个 Agent 只抛这一种受检约束的运行时异常，携带 {@link AgentErrorCode}，
 * 由统一异常处理器转换为 {@link AgentResult}。杜绝各层散乱 try-catch、
 * 返回 null 或裸抛 RuntimeException。</p>
 *
 * @author ZeroAi
 */
public class AgentException extends RuntimeException {

    private final AgentErrorCode errorCode;

    public AgentException(AgentErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public AgentException(AgentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentException(AgentErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public AgentErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 非空断言，失败时抛出 INVALID_ARGUMENT。
     */
    public static <T> T requireNonNull(T value, String field) {
        if (value == null) {
            throw new AgentException(AgentErrorCode.INVALID_ARGUMENT, field + " 不能为空");
        }
        return value;
    }

    /**
     * 非空白字符串断言。
     */
    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AgentException(AgentErrorCode.INVALID_ARGUMENT, field + " 不能为空白");
        }
        return value;
    }

    /**
     * 条件断言，不满足时抛出指定错误码。
     */
    public static void requireState(boolean condition, AgentErrorCode code, String message) {
        if (!condition) {
            throw new AgentException(code, message);
        }
    }
}