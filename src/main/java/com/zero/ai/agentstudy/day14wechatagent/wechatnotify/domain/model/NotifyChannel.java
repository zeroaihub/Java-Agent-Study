package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model;

/**
 * 通知渠道枚举。
 *
 * <p>企业级通知中心通常支持多渠道触达，本项目内置以下渠道。不同渠道对消息格式、
 * 长度、频率限制的要求不同，由各自的 {@code NotifySender} 实现负责适配。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public enum NotifyChannel {

    /** 企业微信应用消息 / 群机器人（本项目主渠道）。 */
    WECHAT_WORK("企业微信"),

    /** 电子邮件。 */
    EMAIL("邮件"),

    /** 短信。 */
    SMS("短信"),

    /** 通用 Webhook 回调（推送到第三方系统）。 */
    WEBHOOK("Webhook");

    private final String displayName;

    NotifyChannel(String displayName) {
        this.displayName = displayName;
    }

    /** @return 渠道中文名 */
    public String displayName() {
        return displayName;
    }
}