package com.zero.ai.agentstudy.day4memory.chapter6;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL 风格用户画像 Repository。
 *
 * 教学版使用内存 Map 模拟 user_profile 表。
 */
@Repository
public class MysqlLikeUserProfileRepository implements UserProfileRepository {

    private final Map<String, Chapter6UserProfile> profiles = new ConcurrentHashMap<>();

    @Override
    public Optional<Chapter6UserProfile> findByUserId(String userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public Chapter6UserProfile save(Chapter6UserProfile profile) {
        profiles.put(profile.getUserId(), profile);
        return profile;
    }
}

