package com.zero.ai.agentstudy.back.bot.service;

import com.zero.ai.agentstudy.back.bot.dto.*;
import com.zero.ai.agentstudy.back.bot.dto.ChatReply;
import com.zero.ai.agentstudy.back.bot.dto.ChatRequest;
import com.zero.ai.agentstudy.back.bot.dto.SummaryResult;
import com.zero.ai.agentstudy.back.bot.dto.TokenUsage;
import com.zero.ai.agentstudy.back.bot.prompt.PromptTemplates;
import com.zero.ai.agentstudy.back.bot.session.ChatSession;
import com.zero.ai.agentstudy.back.bot.session.ChatSessionStore;
import com.zero.ai.agentstudy.back.model.ChatCompletionChunk;
import com.zero.ai.agentstudy.back.model.ChatCompletionRequest;
import com.zero.ai.agentstudy.back.model.ChatCompletionResponse;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 聊天机器人核心服务
 *
 * 整合5大能力:
 *   1. 普通聊天(含多轮对话)
 *   2. 流式输出(含多轮对话)
 *   3. JSON结构化输出(对话总结)
 *   4. Token 统计
 *   5. 会话管理
 *
 * @author ZeroAi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatBotService {

    private final AiService aiService;
    private final ChatSessionStore sessionStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 模拟定价(元/千token) */
    private static final double INPUT_PRICE = 0.001;
    private static final double OUTPUT_PRICE = 0.002;

    // ========== 1. 普通聊天(多轮对话 + token统计) ==========

    /**
     * 普通聊天
     *
     * 流程:
     *   ① 获取/创建会话
     *   ② 拼接 system + 截断历史 + 本次输入
     *   ③ 调用大模型(同步)
     *   ④ 把本轮 user+assistant 消息存回历史
     *   ⑤ 累加 token 统计
     */
    public ChatReply chat(ChatRequest req) {
        // ① 会话管理(多轮对话的基础)
        String sessionId = ensureSessionId(req.getSessionId());
        String systemPrompt = req.getSystemPrompt() != null
                ? req.getSystemPrompt()
                : PromptTemplates.DEFAULT_ASSISTANT;
        ChatSession session = sessionStore.getOrCreate(sessionId, systemPrompt);

        // ② 构造完整消息列表(知识点3: 数组承载历史)
        List<ChatMessage> messages = buildMessages(session, req.getMessage());

        // ③ 构造请求(知识点2: 参数控制)
        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(messages)
                .temperature(req.getTemperature() != null ? req.getTemperature() : 0.7)
                .build();

        // ④ 调用大模型
        ChatCompletionResponse resp = aiService.chat(request);
        String answer = resp.getChoices().get(0).getMessage().getContent();

        // ⑤ 存历史(知识点6: 多轮对话的关键)
        session.appendMessage(ChatMessage.builder().role("user").content(req.getMessage()).build());
        session.appendMessage(ChatMessage.builder().role("assistant").content(answer).build());

        // ⑥ token统计(知识点7)
        ChatCompletionResponse.Usage usage = resp.getUsage();
        double cost = calcCost(usage.getPromptTokens(), usage.getCompletionTokens());
        session.getUsage().accumulate(usage.getPromptTokens(), usage.getCompletionTokens(), cost);
        sessionStore.save(session);

        return ChatReply.builder()
                .sessionId(sessionId)
                .answer(answer)
                .usage(session.getUsage())
                .build();
    }

    // ========== 2. 流式输出(多轮对话) ==========

    /**
     * 流式聊天
     *
     * 难点: 流式响应没有完整的 usage, 需要拼接完整回复后手动存历史
     * token 统计在流式下可能缺失, 这里用 chunk 中的 usage(如有)
     */
    public void streamChat(ChatRequest req, SseEmitter emitter) {
        String sessionId = ensureSessionId(req.getSessionId());
        String systemPrompt = req.getSystemPrompt() != null
                ? req.getSystemPrompt()
                : PromptTemplates.DEFAULT_ASSISTANT;
        ChatSession session = sessionStore.getOrCreate(sessionId, systemPrompt);

        List<ChatMessage> messages = buildMessages(session, req.getMessage());

        StringBuilder fullReply = new StringBuilder();

        aiService.streamChat(messages)
                .doOnNext(chunk -> {
                    String delta = extractDelta(chunk);
                    if (delta != null && !delta.isEmpty()) {
                        fullReply.append(delta);
                        try {
                            emitter.send(SseEmitter.event().data(delta));
                        } catch (IOException e) {
                            log.error("推送失败", e);
                        }
                    }
                })
                .doOnComplete(() -> {
                    // 流式结束后, 把完整回复存回历史
                    String reply = fullReply.toString();
                    session.appendMessage(ChatMessage.builder().role("user").content(req.getMessage()).build());
                    session.appendMessage(ChatMessage.builder().role("assistant").content(reply).build());
                    sessionStore.save(session);
                    try {
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();
                    } catch (IOException e) {
                        log.error("关闭失败", e);
                    }
                })
                .doOnError(emitter::completeWithError)
                .subscribe();
    }

    // ========== 3. JSON结构化输出(对话总结) ==========

    /**
     * 总结对话(知识点8: 结构化输出)
     * 用 response_format=json_object 强制输出 JSON
     */
    public SummaryResult summarize(String sessionId) {
        ChatSession session = sessionStore.get(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));

        // 把历史对话拼成文本
        StringBuilder dialog = new StringBuilder();
        for (ChatMessage msg : session.getMessages()) {
            dialog.append("user".equals(msg.getRole()) ? "用户: " : "AI: ")
                  .append(msg.getContent()).append("\n");
        }

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(
                        ChatMessage.builder().role("system")
                                .content(PromptTemplates.SUMMARIZE_CONVERSATION).build(),
                        ChatMessage.builder().role("user")
                                .content("请总结以下对话:\n" + dialog).build()
                ))
                .temperature(0.0)   // 结构化输出用低温度
                .responseFormat(ChatCompletionRequest.ResponseFormat.builder()
                        .type("json_object").build())
                .build();

        String json = aiService.chat(request).getChoices().get(0).getMessage().getContent();
        try {
            return objectMapper.readValue(json.trim(), SummaryResult.class);
        } catch (Exception e) {
            log.error("解析总结JSON失败: {}", json, e);
            throw new RuntimeException("AI输出格式异常: " + json, e);
        }
    }

    // ========== 4. Token统计 ==========

    public TokenUsage getUsage(String sessionId) {
        return sessionStore.get(sessionId)
                .map(ChatSession::getUsage)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + sessionId));
    }

    // ========== 5. 会话管理 ==========

    public void clearSession(String sessionId) {
        sessionStore.clear(sessionId);
    }

    public List<ChatMessage> getHistory(String sessionId) {
        return sessionStore.get(sessionId)
                .map(ChatSession::getMessages)
                .orElse(List.of());
    }

    // ========== 私有工具方法 ==========

    /**
     * 构造发送给模型的消息列表
     * = system + 截断历史 + 本次输入
     */
    private List<ChatMessage> buildMessages(ChatSession session, String userInput) {
        List<ChatMessage> messages = new ArrayList<>();

        // system 永远在最前
        if (session.getSystemPrompt() != null) {
            messages.add(ChatMessage.builder().role("system")
                    .content(session.getSystemPrompt()).build());
        }

        // 截断历史(滑动窗口, 防止上下文爆炸)
        List<ChatMessage> history = session.getMessages();
        int max = PromptTemplates.MAX_HISTORY_MESSAGES;
        if (history.size() > max) {
            history = history.subList(history.size() - max, history.size());
        }
        messages.addAll(history);

        // 本次输入
        messages.add(ChatMessage.builder().role("user").content(userInput).build());
        return messages;
    }

    private String extractDelta(ChatCompletionChunk chunk) {
        if (chunk.getChoices() == null || chunk.getChoices().isEmpty()) {
            return null;
        }
        ChatMessage delta = chunk.getChoices().get(0).getDelta();
        return delta != null ? delta.getContent() : null;
    }

    private double calcCost(int promptTokens, int completionTokens) {
        return promptTokens * INPUT_PRICE / 1000 + completionTokens * OUTPUT_PRICE / 1000;
    }

    private String ensureSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : sessionId;
    }
}
