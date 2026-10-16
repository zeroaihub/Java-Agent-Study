package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model;

import java.util.Objects;

/**
 * 单次投递结果值对象（不可变 record）。
 *
 * <p>{@code NotifySender.send} 返回它来表达一次投递的结局，而非抛异常——让通知中心
 * 能统一、显式地处理「成功 / 失败」两种分支（失败信息用于重试判断与日志）。</p>
 *
 * @param success   是否投递成功
 * @param messageId 渠道侧返回的消息 ID（成功时；可空）
 * @param error     错误信息（失败时；可空）
 * @author ZeroHub AI Agent Platform
 */
public record SendResult(boolean success, String messageId, String error) {

    /** 紧凑构造器：成功时 error 应为空，失败时应有 error。 */
    public SendResult {
        if (success) {
            error = null;
        } else {
            error = Objects.requireNonNullElse(error, "未知投递错误");
        }
    }

    /**
     * 构造成功结果。
     *
     * @param messageId 渠道消息 ID
     * @return 成功结果
     */
    public static SendResult ok(String messageId) {
        return new SendResult(true, messageId, null);
    }

    /**
     * 构造失败结果。
     *
     * @param error 错误信息
     * @return 失败结果
     */
    public static SendResult fail(String error) {
        return new SendResult(false, null, error);
    }
}