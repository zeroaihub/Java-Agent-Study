package com.zero.ai.agentstudy.day02llmapi.service.impl;

import com.zero.ai.agentstudy.day02llmapi.config.Day02ChatProperties;
import com.zero.ai.agentstudy.day02llmapi.dto.ChatRequest;
import com.zero.ai.agentstudy.day02llmapi.dto.ChatResponse;
import com.zero.ai.agentstudy.day02llmapi.service.Day02ChatService;
import com.zero.ai.agentstudy.day02llmapi.session.ConversationStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * Day02 聊天业务实现。
 * <p>
 * 落地第五章分层 + 第六章企业级增强：结构化日志 + traceId、限流、
 * 参数处理、Token 采集、多轮会话、异常抛出（交异常处理器兜底）。
 */
@Slf4j
@Service
public class Day02ChatServiceImpl implements Day02ChatService {

    private final ChatClient chatClient;
    private final ConversationStore conversationStore;
    private final Day02ChatProperties properties;

    /** 限流器（第六章：令牌桶）。每秒放行 permitsPerSecond 个请求，JDK 自实现，无三方依赖。 */
    private final SimpleRateLimiter rateLimiter;

    /** 配置文件中的默认模型名 */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String defaultModel;

    public Day02ChatServiceImpl(@Qualifier("day02ChatClient") ChatClient chatClient,
                                ConversationStore conversationStore,
                                Day02ChatProperties properties) {
        this.chatClient = chatClient;
        this.conversationStore = conversationStore;
        this.properties = properties;
        this.rateLimiter = new SimpleRateLimiter(properties.getPermitsPerSecond());
    }

    // ================= 能力①：非流式对话 =================
    @Override
    public ChatResponse chat(ChatRequest request) {
        acquirePermit();
        String traceId = newTraceId();
        long start = System.currentTimeMillis();
        // 脱敏：只记长度，不记原文
        log.info("[{}][Day02-Chat] 请求, msgLen={}, hasSystem={}, temperature={}",
                traceId, request.getMessage().length(),
                StringUtils.hasText(request.getSystemPrompt()), request.getTemperature());

        try {
            org.springframework.ai.chat.model.ChatResponse resp = chatClient.prompt()
                    .system(sp -> {
                        if (StringUtils.hasText(request.getSystemPrompt())) {
                            sp.text(request.getSystemPrompt());
                        }
                    })
                    .options(OpenAiChatOptions.builder()
                            .temperature(resolveTemperature(request))
                            .build())
                    .user(request.getMessage())
                    .call()
                    .chatResponse();

            ChatResponse dto = build(resp, null, start);
            log.info("[{}][Day02-Chat] 成功, costMs={}, totalTokens={}",
                    traceId, dto.getCostMs(), dto.getTotalTokens());
            return dto;
        } catch (Exception e) {
            log.error("[{}][Day02-Chat] 失败, costMs={}", traceId,
                    System.currentTimeMillis() - start, e);
            throw new RuntimeException("调用大模型失败：" + e.getMessage(), e);
        }
    }

    // ================= 能力②：流式对话 =================
    @Override
    public Flux<String> chatStream(ChatRequest request) {
        acquirePermit();
        String traceId = newTraceId();
        log.info("[{}][Day02-Stream] 请求, msgLen={}", traceId, request.getMessage().length());

        return chatClient.prompt()
                .system(sp -> {
                    if (StringUtils.hasText(request.getSystemPrompt())) {
                        sp.text(request.getSystemPrompt());
                    }
                })
                .options(OpenAiChatOptions.builder()
                        .temperature(resolveTemperature(request))
                        .build())
                .user(request.getMessage())
                .stream()
                .content()
                .doOnComplete(() -> log.info("[{}][Day02-Stream] 完成", traceId))
                .doOnCancel(() -> log.warn("[{}][Day02-Stream] 客户端取消/断连", traceId))
                .onErrorResume(e -> {
                    log.error("[{}][Day02-Stream] 失败", traceId, e);
                    return Flux.just("[AI 服务繁忙，请稍后重试]");
                });
    }

    // ================= 能力③：多轮会话 =================
    @Override
    public ChatResponse chatMulti(ChatRequest request) {
        acquirePermit();
        if (!StringUtils.hasText(request.getConversationId())) {
            throw new IllegalArgumentException("多轮会话必须传 conversationId");
        }
        String conversationId = request.getConversationId();
        String traceId = newTraceId();
        long start = System.currentTimeMillis();
        log.info("[{}][Day02-Multi] 请求, conversationId={}, msgLen={}",
                traceId, conversationId, request.getMessage().length());

        try {
            // 取出历史消息，连同本轮用户消息一起发给模型
            List<Message> history = conversationStore.getHistory(conversationId);

            org.springframework.ai.chat.model.ChatResponse resp = chatClient.prompt()
                    .messages(history)                 // 携带历史上下文
                    .options(OpenAiChatOptions.builder()
                            .temperature(resolveTemperature(request))
                            .build())
                    .user(request.getMessage())
                    .call()
                    .chatResponse();

            ChatResponse dto = build(resp, conversationId, start);
            // 把本轮对话写回历史，供下次携带
            conversationStore.append(conversationId, request.getMessage(), dto.getContent());

            log.info("[{}][Day02-Multi] 成功, costMs={}, totalTokens={}",
                    traceId, dto.getCostMs(), dto.getTotalTokens());
            return dto;
        } catch (Exception e) {
            log.error("[{}][Day02-Multi] 失败, costMs={}", traceId,
                    System.currentTimeMillis() - start, e);
            throw new RuntimeException("多轮会话调用失败：" + e.getMessage(), e);
        }
    }

    // ================= 私有工具方法 =================

    /** 限流：拿不到令牌直接拒绝，返回 429（由异常处理器转换）。 */
    private void acquirePermit() {
        if (!rateLimiter.tryAcquire()) {
            throw new IllegalStateException("请求太频繁，请稍后再试");
        }
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private double resolveTemperature(ChatRequest request) {
        return request.getTemperature() != null
                ? request.getTemperature()
                : properties.getDefaultTemperature();
    }

    /** 把 Spring AI 的 ChatResponse 组装为对外 DTO，并采集 Token 用量。 */
    private ChatResponse build(org.springframework.ai.chat.model.ChatResponse resp,
                               String conversationId, long start) {
        String content = resp.getResult().getOutput().getText();
        Integer prompt = null, completion = null, total = null;
        if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
            Usage usage = resp.getMetadata().getUsage();
            prompt = usage.getPromptTokens();
            completion = usage.getCompletionTokens();
            total = usage.getTotalTokens();
        }
        return ChatResponse.builder()
                .content(content)
                .model(defaultModel)
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .conversationId(conversationId)
                .costMs(System.currentTimeMillis() - start)
                .fallback(false)
                .build();
    }

    /**
     * 极简令牌桶限流器（JDK 自实现，替代 Guava RateLimiter，避免引入三方依赖）。
     * <p>按固定速率补充令牌，tryAcquire 拿到令牌返回 true，否则 false。线程安全。
     */
    static class SimpleRateLimiter {
        private final double permitsPerSecond;
       private final double maxPermits;
        private double storedPermits;
        private long lastRefillNanos;

        SimpleRateLimiter(double permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond <= 0 ? 1 : permitsPerSecond;
            this.maxPermits = this.permitsPerSecond;      // 桶容量 = 1 秒的量
            this.storedPermits = this.maxPermits;         // 初始装满
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            refill();
            if (storedPermits >= 1) {
                storedPermits -= 1;
                return true;
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSec > 0) {
                storedPermits = Math.min(maxPermits, storedPermits + elapsedSec * permitsPerSecond);
                lastRefillNanos = now;
            }
        }
    }
}