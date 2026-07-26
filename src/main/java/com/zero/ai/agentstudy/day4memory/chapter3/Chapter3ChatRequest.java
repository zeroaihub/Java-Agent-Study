package com.zero.ai.agentstudy.day4memory.chapter3;

/**
 * 第三章聊天请求。
 *
 * @param sessionId 会话 ID
 * @param message   用户消息
 */
public record Chapter3ChatRequest(
        String sessionId,
        String message
) {
}

