package com.zero.ai.agentstudy.day4memory.chapter8;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最终 Agent 用户画像 Repository。
 */
@Repository
public class FinalProfileRepository {

    private final Map<String, FinalUserProfile> profiles = new ConcurrentHashMap<>();

    public Optional<FinalUserProfile> findByUserId(String userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    public FinalUserProfile save(FinalUserProfile profile) {
        profiles.put(profile.getUserId(), profile);
        return profile;
    }
}

