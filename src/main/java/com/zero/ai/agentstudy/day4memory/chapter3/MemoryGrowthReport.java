package com.zero.ai.agentstudy.day4memory.chapter3;

import java.util.List;

/**
 * Chat Memory 增长报告。
 */
public record MemoryGrowthReport(
        String asciiDiagram,
        List<RoundCost> roundCosts,
        String conclusion
) {

    public record RoundCost(
            int round,
            int messagesInPrompt,
            int estimatedPromptTokens,
            String explanation
    ) {
    }
}

