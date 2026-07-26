package com.zero.ai.agentstudy.day4memory.chapter2;

/**
 * 单条分类结果。
 *
 * @param category 分类
 * @param reason   分类原因
 * @param advice   工程实现建议
 */
public record MemoryClassification(
        MemoryCategoryView category,
        String reason,
        String advice
) {
}

