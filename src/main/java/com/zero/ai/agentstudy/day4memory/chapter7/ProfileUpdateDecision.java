package com.zero.ai.agentstudy.day4memory.chapter7;

/**
 * 用户画像更新决策。
 */
public record ProfileUpdateDecision(
        boolean shouldUpdate,
        String strategy,
        String reason,
        String auditAdvice
) {
}

