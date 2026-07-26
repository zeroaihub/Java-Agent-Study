package com.zero.ai.agentstudy.day4memory.chapter6;

import java.util.List;

/**
 * 第六章 Memory Agent 响应。
 */
public record MemoryChatResponse(
        String conversationId,
        String answer,
        int recentMessageCount,
        Chapter6UserProfile userProfile,
        List<StoredChatMessage> recentMessages,
        String promptPreview
) {
}

