package com.zero.ai.agentstudy.day14wechatagent.wechatcore.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.port.IdempotencyStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等存储的内存实现（本地兜底）。
 * <p>
 * 采用带过期时间的 {@link ConcurrentHashMap}，在无 Redis 的开发/教学环境下
 * 保证消息管线的幂等语义可用。生产环境应由 Redis 实现覆盖本 Bean
 * （通过 {@link ConditionalOnMissingBean} 让位）。
 * <p>
 * 注意：内存实现不具备跨实例一致性，多副本部署时必须切换到 Redis 实现。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@ConditionalOnMissingBean(name = "redisIdempotencyStore")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    /** key -> 过期时间戳（epoch millis） */
    private final ConcurrentHashMap<String, Long> store = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, Duration ttl) {
        long now = Instant.now().toEpochMilli();
        long expireAt = now + ttl.toMillis();

        // compute 保证原子性：仅当键不存在或已过期时才写入并返回“抢占成功”
        final boolean[] acquired = {false};
        store.compute(key, (k, existing) -> {
            if (existing == null || existing <= now) {
                acquired[0] = true;
                return expireAt;
            }
            return existing;
        });

        if (acquired[0]) {
            evictExpiredIfNeeded(now);
        } else {
            log.debug("幂等命中，忽略重复键: {}", key);
        }
        return acquired[0];
    }

    @Override
    public void release(String key) {
        store.remove(key);
    }

    /** 简单的惰性清理：当容量增长时清除已过期键，避免内存无限膨胀。 */
    private void evictExpiredIfNeeded(long now) {
        if (store.size() > 10_000) {
            store.entrySet().removeIf(e -> e.getValue() <= now);
        }
    }
}