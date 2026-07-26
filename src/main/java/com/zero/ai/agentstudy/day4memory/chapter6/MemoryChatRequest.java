package com.zero.ai.agentstudy.day4memory.chapter6;

/**
 * 第六章 Memory Agent 请求。
 */
public record MemoryChatRequest(
        String userId,
        String sessionId,
        String message
) {
}

