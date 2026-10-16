package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model;

/**
 * 消息内容类型（值对象-枚举）。
 * <p>
 * 覆盖企业级 IM Agent 需要处理的主要消息形态。入站解析与出站渲染
 * 都以此为分类依据。
 *
 * @author ZeroAi
 */
public enum MessageType {

    /** 纯文本 */
    TEXT("text"),

    /** Markdown（机器人回复的首选格式，支持标题/列表/代码块/链接） */
    MARKDOWN("markdown"),

    /** 图片（需图片理解时进入多模态链路） */
    IMAGE("image"),

    /** 文件（文档/表格/PDF，进入文件解析或上传链路） */
    FILE("file"),

    /** 图文卡片（如审批卡片、任务卡片） */
    CARD("card"),

    /** 语音（预留） */
    VOICE("voice");

    private final String code;

    MessageType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static MessageType of(String code) {
        if (code == null || code.isBlank()) {
            return TEXT;
        }
        for (MessageType t : values()) {
            if (t.code.equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        return TEXT;
    }
}