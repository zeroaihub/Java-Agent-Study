package com.zero.ai.agentstudy.day4memory.chapter7;

/**
 * 压缩策略决策。
 */
public record CompressionDecision(
        boolean shouldCompress,
        String strategy,
        String reason,
        String implementationAdvice
) {
}

