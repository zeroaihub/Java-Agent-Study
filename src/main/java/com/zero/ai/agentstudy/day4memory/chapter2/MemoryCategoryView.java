package com.zero.ai.agentstudy.day4memory.chapter2;

/**
 * Memory 分类展示 DTO。
 */
public record MemoryCategoryView(
        String code,
        String title,
        String definition,
        String typicalStorage,
        String lifecycle,
        String lifeExample,
        String agentExample
) {

    public static MemoryCategoryView from(MemoryCategory category) {
        return new MemoryCategoryView(
                category.getCode(),
                category.getTitle(),
                category.getDefinition(),
                category.getTypicalStorage(),
                category.getLifecycle(),
                category.getLifeExample(),
                category.getAgentExample()
        );
    }
}

