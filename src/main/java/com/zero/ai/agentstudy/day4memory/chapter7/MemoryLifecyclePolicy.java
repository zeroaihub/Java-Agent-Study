package com.zero.ai.agentstudy.day4memory.chapter7;

/**
 * Memory 生命周期策略。
 */
public record MemoryLifecyclePolicy(
        MemoryType memoryType,
        String storage,
        String ttl,
        String updateStrategy,
        String deleteStrategy,
        String riskControl
) {
}

