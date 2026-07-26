package com.zero.ai.agentstudy.day02llmapi.service;

import com.zero.ai.agentstudy.day02llmapi.dto.ChatRequest;
import com.zero.ai.agentstudy.day02llmapi.dto.ChatResponse;
import reactor.core.publisher.Flux;

/**
 * Day02 聊天业务服务接口。
 * <p>
 * 定义三大核心能力：非流式对话、流式对话、多轮会话。
 */
public interface Day02ChatService {

    /**
     * 能力①：非流式对话。等模型生成完，一次性返回完整回答与 Token 用量。
     */
    ChatResponse chat(ChatRequest request);

    /**
     * 能力②：流式对话。逐 chunk 返回增量文本（SSE），用于打字机效果。
     */
    Flux<String> chatStream(ChatRequest request);

    /**
     * 能力③：多轮会话。基于 conversationId 携带历史上下文，AI 能记住上文。
     */
    ChatResponse chatMulti(ChatRequest request);
}