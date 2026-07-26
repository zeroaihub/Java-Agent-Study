package com.zero.ai.agentstudy.day4memory.chapter3;

/**
 * Memory 策略展示 DTO。
 */
public record MemoryStrategyView(
        String code,
        String title,
        String description
) {

    public static MemoryStrategyView from(MemoryStrategy strategy) {
        return new MemoryStrategyView(
                strategy.getCode(),
                strategy.getTitle(),
                strategy.getDescription()
        );
    }
}

