package com.zero.ai.agentstudy.day4memory.chapter3;

import com.zero.ai.agentstudy.back.model.ChatMessage;

import java.util.List;

/**
 * 第三章聊天响应。
 *
 * @param strategy              Memory 控制策略
 * @param sessionId             会话 ID
 * @param answer                模拟回答
 * @param fullHistoryMessages   完整历史消息数
 * @param promptMessages        本次会发给 LLM 的消息数
 * @param estimatedPromptTokens 估算 prompt token 数
 * @param memorySnapshot        当前会话完整历史快照
 * @param promptPreview         本次实际会注入 LLM 的上下文预览
 */
public record Chapter3ChatResponse(
        String strategy,
        String sessionId,
        String answer,
        int fullHistoryMessages,
        int promptMessages,
        int estimatedPromptTokens,
        List<ChatMessage> memorySnapshot,
        List<ChatMessage> promptPreview
) {
}

