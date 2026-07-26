package com.zero.ai.agentstudy.day4memory.chapter8;

import java.time.Instant;

/**
 * 最终 Agent 聊天消息。
 */
public record FinalChatMessage(
        String role,
        String content,
        Instant createdAt
) {

    public static FinalChatMessage of(String role, String content) {
        return new FinalChatMessage(role, content, Instant.now());
    }
}

