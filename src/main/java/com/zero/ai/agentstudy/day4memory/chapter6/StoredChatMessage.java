package com.zero.ai.agentstudy.day4memory.chapter6;

import java.time.Instant;

/**
 * 存储版聊天消息。
 */
public record StoredChatMessage(
        String role,
        String content,
        Instant createdAt
) {

    public static StoredChatMessage of(String role, String content) {
        return new StoredChatMessage(role, content, Instant.now());
    }
}

