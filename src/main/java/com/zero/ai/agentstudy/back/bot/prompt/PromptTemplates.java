package com.zero.ai.agentstudy.back.bot.prompt;

/**
 * Prompt 模板集中管理
 *
 * 企业级规范: 所有 prompt 集中管理, 便于维护和版本化
 * (生产环境可改为从数据库/配置中心加载)
 *
 * @author ZeroAi
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    /** 默认助手人设 */
    public static final String DEFAULT_ASSISTANT = """
            你是一个友好、专业的AI助手。
            回答要求:
            1. 简洁明了, 重点突出
            2. 如果不确定, 坦诚告知
            3. 适合中文用户阅读
            """;

    /**
     * 对话总结 prompt(用于结构化输出场景)
     * 输出 JSON 格式
     */
    public static final String SUMMARIZE_CONVERSATION = """
            你是对话分析助手。请总结对话内容, 必须且只输出合法JSON, 格式:
            {"summary":"一句话总结","keyPoints":["要点1","要点2"],"sentiment":"positive|neutral|negative"}
            不要输出任何其他文字。
            """;

    /** 最大保留消息数(滑动窗口) */
    public static final int MAX_HISTORY_MESSAGES = 20;
}
