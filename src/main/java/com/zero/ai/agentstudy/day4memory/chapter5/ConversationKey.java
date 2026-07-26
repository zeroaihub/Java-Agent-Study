package com.zero.ai.agentstudy.day4memory.chapter5;

/**
 * 会话身份。
 */
public record ConversationKey(
        String userId,
        String sessionId
) {

    public String conversationId() {
        return userId + ":" + sessionId;
    }
}

