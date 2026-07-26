package com.zero.ai.agentstudy.day4memory.chapter5;

import java.time.Instant;

/**
 * 框架无关的消息对象。
 */
public record FrameworkMessage(
        String role,
        String content,
        Instant createdAt
) {

    public static FrameworkMessage of(String role, String content) {
        return new FrameworkMessage(role, content, Instant.now());
    }
}

