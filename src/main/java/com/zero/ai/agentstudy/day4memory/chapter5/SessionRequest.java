package com.zero.ai.agentstudy.day4memory.chapter5;

/**
 * 第五章会话请求。
 */
public record SessionRequest(
        String userId,
        String sessionId,
        String message
) {
}

