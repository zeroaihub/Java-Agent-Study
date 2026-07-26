package com.zero.ai.agentstudy.day4memory.chapter7;

/**
 * 用户画像更新策略请求。
 */
public record ProfileUpdateRequest(
        String field,
        String oldValue,
        String newValue,
        double confidence,
        boolean userExplicitlyConfirmed
) {
}

