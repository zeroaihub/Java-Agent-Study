package com.zero.ai.agentstudy.day4memory.chapter4;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 教学版用户画像 Repository。
 *
 * 生产环境替换为 MySQL/JPA/MyBatis 实现即可，Service 层不需要改业务语义。
 */
@Repository
public class InMemoryUserProfileRepository {

    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();

    public Optional<UserProfile> findByUserId(String userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    public UserProfile save(UserProfile profile) {
        profiles.put(profile.getUserId(), profile);
        return profile;
    }
}

