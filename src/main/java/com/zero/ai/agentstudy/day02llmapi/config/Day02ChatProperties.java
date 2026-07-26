package com.zero.ai.agentstudy.day02llmapi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Day02 外置配置（对应 application.yml 中 day02.chat.*）。
 * <p>
 * 体现第六章「配置外置」：改行为只改 yml，不动代码。
 * 全部提供默认值，未配置也能独立运行。
 */
@Data
@Component
@ConfigurationProperties(prefix = "day02.chat")
public class Day02ChatProperties {

    /** 默认 System Prompt（人设） */
    private String defaultSystemPrompt = "你是 Day02 AI Chat Service，一位专业、严谨、友好的 AI 助手，回答逻辑清晰、简洁准确。";

    /** 默认温度 */
    private Double defaultTemperature = 0.7;

    /** 每秒允许的请求数（限流阈值） */
    private double permitsPerSecond = 10.0;

    /** 多轮会话最多保留的历史消息条数（防止上下文无限增长） */
    private int maxHistorySize = 20;
}