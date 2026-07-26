package com.zero.ai.agentstudy.day4memory.chapter1;

/**
 * Day4 第一章聊天请求。
 *
 * @param sessionId 会话 ID；with-memory 模式必填，用于隔离不同会话
 * @param message   用户输入
 */
public record Chapter1ChatRequest(
        String sessionId,
        String message
) {
}

