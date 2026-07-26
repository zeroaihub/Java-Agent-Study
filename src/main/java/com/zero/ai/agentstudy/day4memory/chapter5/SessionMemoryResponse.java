package com.zero.ai.agentstudy.day4memory.chapter5;

import java.util.List;

/**
 * Session Memory 响应。
 */
public record SessionMemoryResponse(
        String conversationId,
        int messageCount,
        List<FrameworkMessage> messages,
        String designExplanation
) {
}

