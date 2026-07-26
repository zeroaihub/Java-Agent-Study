package com.zero.ai.agentstudy.day4memory.chapter1;

import com.zero.ai.agentstudy.back.model.ChatMessage;

import java.util.List;

/**
 * Day4 第一章聊天响应。
 *
 * @param mode          演示模式：no-memory / with-memory
 * @param sessionId     会话 ID
 * @param answer        大模型回答
 * @param messagesSent  本次实际发送给 LLM 的消息数量
 * @param memorySnapshot 当前会话已保存的记忆快照
 */
public record Chapter1ChatResponse(
        String mode,
        String sessionId,
        String answer,
        int messagesSent,
        List<ChatMessage> memorySnapshot
) {
}

