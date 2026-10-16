package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.exception;

/**
 * WeChat Agent 领域统一异常。
 * <p>
 * 领域层与应用层抛出的业务异常统一收敛到此类型，携带稳定的错误码
 * {@link ErrorCode}，便于 API 层做统一异常处理、日志归类与前端展示。
 * <p>
 * 与技术异常（IO、网络）区分：技术异常应包装为对应错误码后再抛出，
 * 避免底层堆栈直接穿透到用户。
 *
 * @author ZeroAi
 */
public class WeChatAgentException extends RuntimeException {

    /**
     * 领域错误码枚举。
     * <p>
     * code 采用「模块前缀 + 序号」的稳定字符串，方便日志检索与告警配置。
     */
    public enum ErrorCode {
        /** 参数非法 */
        INVALID_ARGUMENT("WA-400", "参数非法"),
        /** 租户未授权 / 配额超限 */
        TENANT_FORBIDDEN("WA-403", "租户无权限或配额超限"),
        /** 会话不存在 */
        CONVERSATION_NOT_FOUND("WA-404", "会话不存在"),
        /** 重复消息（幂等拦截） */
        DUPLICATE_MESSAGE("WA-409", "重复消息，已幂等忽略"),
        /** 渠道不支持 */
        CHANNEL_UNSUPPORTED("WA-422", "不支持的渠道类型"),
        /** 渠道投递失败 */
        CHANNEL_DELIVERY_FAILED("WA-502", "渠道消息投递失败"),
        /** Agent 编排失败 */
        ORCHESTRATION_FAILED("WA-500", "Agent 编排执行失败");

        private final String code;
        private final String defaultMessage;

        ErrorCode(String code, String defaultMessage) {
            this.code = code;
            this.defaultMessage = defaultMessage;
        }

        public String getCode() {
            return code;
        }

        public String getDefaultMessage() {
            return defaultMessage;
        }
    }

    private final ErrorCode errorCode;

    public WeChatAgentException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public WeChatAgentException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WeChatAgentException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 便捷工厂：重复消息。 */
    public static WeChatAgentException duplicate(String idempotencyKey) {
        return new WeChatAgentException(ErrorCode.DUPLICATE_MESSAGE,
                "重复消息已忽略: " + idempotencyKey);
    }
}