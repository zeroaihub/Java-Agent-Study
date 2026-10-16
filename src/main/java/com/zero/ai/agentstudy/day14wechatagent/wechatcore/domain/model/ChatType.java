package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model;

/**
 * 聊天类型（值对象-枚举）。
 * <p>
 * 单聊与群聊在「路由策略、@提及处理、回复方式」上差异巨大：
 * <ul>
 *     <li>单聊：默认响应所有消息；回复直接发给用户。</li>
 *     <li>群聊：默认只响应 @机器人 的消息（避免噪音）；回复发到群。</li>
 * </ul>
 *
 * @author ZeroAi
 */
public enum ChatType {

    /** 单聊（一对一） */
    SINGLE("single"),

    /** 群聊（多人 + 机器人） */
    GROUP("group");

    private final String code;

    ChatType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ChatType of(String code) {
        if (code != null && "group".equalsIgnoreCase(code.trim())) {
            return GROUP;
        }
        return SINGLE;
    }
}