package com.zero.ai.agentstudy.day4memory.chapter8;

/**
 * 第八章最终 Memory Agent 请求。
 */
public record FinalMemoryChatRequest(
        String userId,
        String sessionId,
        String message
) {
}

