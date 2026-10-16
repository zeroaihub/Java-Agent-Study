package com.zero.ai.agentstudy.day14wechatagent.wechatrouter.domain.model;

/**
 * 意图类型：路由器对一条入站消息的初步分类。
 * <p>
 * 意图识别是路由的第一步——先判断「用户想干什么」，再决定「交给谁处理」。
 * 本枚举刻意保持粗粒度，细粒度的命令识别留给命令系统（第五章）。
 *
 * @author ZeroAi
 */
public enum IntentType {

    /** 命令：以特定前缀（如 "/" 或 "帮我"）触发的结构化指令。 */
    COMMAND("command", "命令"),

    /** 闲聊 / 问答：自由文本，交给大模型对话链路。 */
    CHAT("chat", "闲聊问答"),

    /** 多模态：图片 / 文件，需要走解析或视觉理解链路。 */
    MULTIMODAL("multimodal", "多模态"),

    /** 未知 / 无需处理：无法识别或不应响应的消息。 */
    UNKNOWN("unknown", "未知");

    private final String code;
    private final String label;

    IntentType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}