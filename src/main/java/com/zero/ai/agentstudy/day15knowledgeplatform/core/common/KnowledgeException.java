package com.zero.ai.agentstudy.day15knowledgeplatform.core.common;

/**
 * 知识平台统一领域异常。
 *
 * <p>所有业务层抛出的可预期异常都应使用本类，携带 {@link KnowledgeErrorCode}，
 * 由统一异常处理器转换为 {@link KnowledgeResult}。避免在业务代码里到处 try-catch
 * 与裸抛 RuntimeException。</p>
 *
 * @author ZeroAi
 */
public class KnowledgeException extends RuntimeException {

    private final KnowledgeErrorCode errorCode;

    public KnowledgeException(KnowledgeErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public KnowledgeException(KnowledgeErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public KnowledgeException(KnowledgeErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public KnowledgeErrorCode getErrorCode() {
        return errorCode;
    }

    // ---------- 便捷静态工厂 ----------

    public static KnowledgeException of(KnowledgeErrorCode code) {
        return new KnowledgeException(code);
    }

    public static KnowledgeException of(KnowledgeErrorCode code, String message) {
        return new KnowledgeException(code, message);
    }

    public static KnowledgeException wrap(KnowledgeErrorCode code, Throwable cause) {
        return new KnowledgeException(code, code.defaultMessage() + ": " + cause.getMessage(), cause);
    }

    /**
     * 断言参数非空，否则抛出 INVALID_ARGUMENT；断言通过时原样返回，便于在构造器中链式赋值。
     */
    public static <T> T requireNonNull(T obj, String field) {
        if (obj == null) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, field + " 不能为空");
        }
        return obj;
    }
}