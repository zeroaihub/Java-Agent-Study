package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model;

/**
 * 渠道类型（值对象-枚举）。
 * <p>
 * Day14 采用「渠道适配器」模式：一套核心逻辑适配所有 IM 渠道。
 * 通过该枚举标识消息来自/去往哪个渠道，出站端口 {@code MessageChannel}
 * 依据它选择对应实现。
 * <p>
 * 企业级铁律：生产环境只做企业微信官方 API / Webhook，绝不碰个人微信协议 hook。
 *
 * @author ZeroAi
 */
public enum ChannelType {

    /** 企业微信自建应用（官方 API，可商用、可 SaaS） */
    WECOM("wecom", "企业微信"),

    /** 通用群机器人 Webhook（POST 即可发消息，最易上手） */
    WEBHOOK("webhook", "通用Webhook"),

    /** 本地 Mock 渠道：无凭证也能全链路联调，仅用于开发/测试 */
    MOCK("mock", "本地Mock"),

    /** 预留：飞书（未来扩展） */
    FEISHU("feishu", "飞书"),

    /** 预留：钉钉（未来扩展） */
    DINGTALK("dingtalk", "钉钉");

    private final String code;
    private final String displayName;

    ChannelType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * 从配置字符串解析渠道类型，未知值回退为 MOCK，保证本地始终可运行。
     *
     * @param code 渠道 code，如 "wecom"
     * @return 匹配的渠道类型，未知则返回 {@link #MOCK}
     */
    public static ChannelType of(String code) {
        if (code == null || code.isBlank()) {
            return MOCK;
        }
        for (ChannelType t : values()) {
            if (t.code.equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        return MOCK;
    }
}