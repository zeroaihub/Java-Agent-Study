package com.zero.ai.agentstudy.day4memory.chapter6;

import java.util.Optional;

/**
 * 长期用户画像 Repository 抽象。
 *
 * 生产环境可用 MySQL/JPA/MyBatis 实现。
 */
public interface UserProfileRepository {

    Optional<Chapter6UserProfile> findByUserId(String userId);

    Chapter6UserProfile save(Chapter6UserProfile profile);
}

