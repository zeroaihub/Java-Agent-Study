package com.zero.ai.agentstudy.day4memory.chapter2;

import java.util.List;

/**
 * Memory 分类响应。
 *
 * @param content         原始内容
 * @param classifications 可能匹配的 Memory 分类
 */
public record MemoryClassifyResponse(
        String content,
        List<MemoryClassification> classifications
) {
}

