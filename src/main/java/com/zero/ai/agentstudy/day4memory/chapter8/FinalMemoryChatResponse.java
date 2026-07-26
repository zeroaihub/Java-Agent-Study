package com.zero.ai.agentstudy.day4memory.chapter8;

import java.util.List;

/**
 * 第八章最终 Memory Agent 响应。
 */
public record FinalMemoryChatResponse(
        String conversationId,
        String answer,
        FinalUserProfile userProfile,
        List<FinalChatMessage> recentMessages,
        String promptPreview,
        List<String> architectureReview
) {
}

