package com.zero.ai.agentstudy.day4memory.chapter4;

/**
 * 长期保存决策结果。
 */
public record FieldDecisionResponse(
        String content,
        boolean shouldSaveLongTerm,
        String targetField,
        String reason,
        String risk
) {
}

