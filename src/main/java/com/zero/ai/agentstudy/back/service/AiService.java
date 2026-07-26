package com.zero.ai.agentstudy.back.service;

import com.zero.ai.agentstudy.back.config.AiProperties;
import com.zero.ai.agentstudy.back.model.ChatCompletionChunk;
import com.zero.ai.agentstudy.back.model.ChatCompletionRequest;
import com.zero.ai.agentstudy.back.model.ChatCompletionResponse;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 大模型调用服务
 *
 * 封装了同步调用与流式调用两种方式, 是所有 Demo 和最终项目的基础。
 * 这里只做"薄封装", 把知识点暴露在 Demo 层, 让你看得更清楚。
 *
 * @author ZeroAi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final WebClient aiWebClient;
    private final AiProperties aiProperties;

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /**
     * 同步对话(最简形式)
     *
     * @param messages 消息列表
     * @return AI 回复文本
     */
    public String chat(List<ChatMessage> messages) {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(aiProperties.getModel())
                .messages(messages)
                .stream(false)
                .build();
        return chat(request).getChoices().get(0).getMessage().getContent();
    }

    /**
     * 同步对话(完整请求)
     * 用于需要 temperature / response_format 等高级参数的场景
     *
     * @param request 完整请求
     * @return 完整响应(含 usage token 统计)
     */
    public ChatCompletionResponse chat(ChatCompletionRequest request) {
        if (request.getModel() == null) {
            request.setModel(aiProperties.getModel());
        }
        if (request.getStream() == null) {
            request.setStream(false);
        }
        log.info("调用大模型: model={}, messageCount={}", request.getModel(),
                request.getMessages() != null ? request.getMessages().size() : 0);
        try {
            // 打印实际发出的请求体, 便于与手工测试对比定位参数问题
            try {
                log.error(">>> 实际请求体: {}", new com.fasterxml.jackson.databind.ObjectMapper()
                        .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                        .writeValueAsString(request));
            } catch (Exception ignore) {
            }
            return aiWebClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ChatCompletionResponse.class)
                    .block();
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            // 打印服务端返回的错误详情, 便于定位 400/422 等参数问题
            String body = e.getResponseBodyAsString();
            log.error(">>> 大模型调用失败: status={}, 响应体={}", e.getStatusCode(), body);
            // 把服务端错误详情附加到异常信息, 确保堆栈里可见
            throw new RuntimeException("大模型返回 " + e.getStatusCode() + ", 详情: " + body, e);
        }
    }

    /**
     * 流式对话
     * 返回 Flux<ChatCompletionChunk>, 每个元素是一个 SSE 数据块
     *
     * @param messages 消息列表
     * @return 数据块流
     */
    public Flux<ChatCompletionChunk> streamChat(List<ChatMessage> messages) {
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(aiProperties.getModel())
                .messages(messages)
                .stream(true)
                .build();
        return streamChat(request);
    }

    /**
     * 流式对话(完整请求)
     */
    public Flux<ChatCompletionChunk> streamChat(ChatCompletionRequest request) {
        if (request.getModel() == null) {
            request.setModel(aiProperties.getModel());
        }
        request.setStream(true);
        log.info("流式调用大模型: model={}", request.getModel());

        return aiWebClient.post()
                .uri(CHAT_COMPLETIONS_PATH)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(ChatCompletionChunk.class)
                // 过滤掉空 delta
                .filter(chunk -> chunk.getChoices() != null && !chunk.getChoices().isEmpty())
                .doOnError(e -> log.error("流式调用失败", e))
                .onErrorResume(e -> Mono.empty());
    }
}
