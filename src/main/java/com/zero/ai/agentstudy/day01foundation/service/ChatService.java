package com.zero.ai.agentstudy.day01foundation.service;

import com.zero.ai.agentstudy.day01foundation.dto.ChatRequest;
import com.zero.ai.agentstudy.day01foundation.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 聊天业务服务
 * <p>
 * 演示 Day1 第五章的五大功能：
 * 1. 普通聊天（最简调用链）
 * 2. 动态 System Prompt（请求级人设覆盖）
 * 3. 动态温度（OpenAiChatOptions 运行时参数）
 * 4. 异常处理（try-catch 兜底）
 * 5. 日志与耗时统计（记入参/出参 length/耗时）
 */
@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;

    /** 配置文件中的默认模型名，缺省为 unknown */
    @Value("${spring.ai.openai.chat.options.model:unknown}")
    private String defaultModel;

    public ChatService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 处理一次聊天请求。
     *
     * @param request 聊天请求（message 必填，systemPrompt / temperature 可选）
     * @return 聊天响应（含回答、模型、耗时）
     */
    public ChatResponse chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        // 功能5：记录入参（问题全文可记，回答只记长度）
        log.info("[Day01-Chat] 收到请求, message={}, systemPrompt={}, temperature={}",
                request.getMessage(), request.getSystemPrompt(), request.getTemperature());

        try {
            // 从最简调用链开始，逐步按需增加能力
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt();

            // 功能2：动态 System Prompt —— 传了就覆盖默认人设
            if (StringUtils.hasText(request.getSystemPrompt())) {
                spec = spec.system(request.getSystemPrompt());
            }

            // 功能3：动态温度 —— 传了就通过 OpenAiChatOptions 运行时注入
            if (request.getTemperature() != null) {
                spec = spec.options(OpenAiChatOptions.builder()
                        .temperature(request.getTemperature()));
            }

            // 功能1：普通聊天（user -> call -> content）
            String answer = spec.user(request.getMessage())
                    .call()
                    .content();

            long cost = System.currentTimeMillis() - start;
            // 功能5：记录出参长度与耗时（不记回答全文，避免日志膨胀）
            log.info("[Day01-Chat] 响应成功, model={}, answerLength={}, costMs={}",
                    defaultModel, answer == null ? 0 : answer.length(), cost);

            return new ChatResponse(answer, defaultModel, cost);
        } catch (Exception e) {
            // 功能4：异常处理 —— 记录错误并抛出，交由全局异常处理器兜底返回 500
            long cost = System.currentTimeMillis() - start;
            log.error("[Day01-Chat] 调用大模型失败, costMs={}, error={}", cost, e.getMessage(), e);
            throw new RuntimeException("调用大模型失败：" + e.getMessage(), e);
        }
    }
}