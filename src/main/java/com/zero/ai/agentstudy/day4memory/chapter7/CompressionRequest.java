package com.zero.ai.agentstudy.day4memory.chapter7;

/**
 * 压缩策略评估请求。
 */
public record CompressionRequest(
        int messageCount,
        int estimatedTokens,
        boolean containsImportantFacts
) {
}

