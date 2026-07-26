package com.zero.ai.agentstudy.day09browseragent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Day09 专用 ChatClient 配置。
 *
 * <p><b>工程约束</b>：前序 Day 已定义了多个 ChatClient Bean。这里使用独立 Bean 名
 * {@code day09ChatClient}，注入时用 {@code @Qualifier} 精确指定，与其它模块共存、
 * 互不冲突（沿用 Day02 起确立的隔离策略）。</p>
 *
 * @author AI架构师
 */
@Configuration
public class Day09ChatClientConfig {

    /**
     * 构建 Day09 专用 ChatClient，复用 Spring AI 自动装配的 Builder。
     *
     * @param builder Spring AI 提供的 ChatClient 构建器
     * @return Day09 专属 ChatClient
     */
    @Bean("day09ChatClient")
    public ChatClient day09ChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是 ZeroHub 平台的浏览器自动化助手。")
                .build();
    }
}