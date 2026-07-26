package com.zero.ai.agentstudy.day4memory.chapter2;

import java.util.List;

/**
 * Memory 分类图响应。
 */
public record MemoryMapResponse(
        String asciiDiagram,
        List<MemoryCategoryView> categories
) {
}

