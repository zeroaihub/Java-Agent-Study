package com.zero.ai.agentstudy.day4memory.chapter4;

import java.util.List;

/**
 * 用户画像增量更新请求。
 */
public record UserProfilePatch(
        String userId,
        String name,
        String profession,
        List<String> skills,
        List<String> interests,
        List<String> learningGoals,
        List<String> preferences,
        List<String> recentProjects,
        double confidence
) {
}

