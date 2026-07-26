package com.zero.ai.agentstudy.day02llmapi.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Day02 专用 ChatClient 配置。
 * <p>
 * <b>关键工程约束</b>：Day1 已经定义了一个名为 {@code chatClient} 的全局 Bean。
 * 这里使用<b>独立 Bean 名 {@code day02ChatClient}</b>，注入时用 {@code @Qualifier} 精确指定，
 * 从而与 Day1 共存、互不冲突。
 */
@Configuration
public class Day02ChatClientConfig {

    private final Day02ChatProperties properties;

    public Day02ChatClientConfig(Day02ChatProperties properties) {
        this.properties = properties;
    }

    /**
     * 构建 Day02 专用 ChatClient。
     * 复用 Spring AI 自动装配的 {@link ChatClient.Builder}，
     * 设置本模块默认人设。
     */
    @Bean("day02ChatClient")
    public ChatClient day02ChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(properties.getDefaultSystemPrompt())
                .build();
    }
}