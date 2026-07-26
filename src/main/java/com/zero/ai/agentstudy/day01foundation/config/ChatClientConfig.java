package com.zero.ai.agentstudy.day01foundation.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 配置类
 * <p>
 * Spring AI 已自动装配 ChatClient.Builder，这里基于它构建一个
 * 带默认 System Prompt 的 ChatClient Bean，供业务层直接注入使用。
 */
@Configuration
public class ChatClientConfig {

    /**
     * 构建全局默认的 ChatClient。
     * defaultSystem 设定 AI 的默认人设，后续调用可被请求级 systemPrompt 覆盖。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是一位专业、友好的AI助手，回答问题时逻辑清晰、简洁准确。")
                .build();
    }
}