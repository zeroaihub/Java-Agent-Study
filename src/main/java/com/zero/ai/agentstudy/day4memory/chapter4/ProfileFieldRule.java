package com.zero.ai.agentstudy.day4memory.chapter4;

/**
 * 长期 Memory 字段保存规则。
 */
public record ProfileFieldRule(
        String field,
        String saveDecision,
        String reason,
        String example,
        String storageAdvice
) {
}

