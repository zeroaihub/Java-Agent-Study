package com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.port;

import java.time.Duration;

/**
 * 出站端口：幂等存储（Idempotent Receiver 支撑设施）。
 * <p>
 * IM 网关普遍存在「至少一次」投递语义，同一条消息可能被重复推送。
 * 应用层在处理入站消息前，先通过本端口以「首次写入成功」语义抢占幂等键，
 * 抢占失败即视为重复消息并幂等忽略。
 * <p>
 * 典型实现：Redis {@code SET key value NX PX ttl}；本地实现可用带过期的
 * 内存 Map，保证无 Redis 环境下课程仍可运行。
 *
 * @author ZeroAi
 */
public interface IdempotencyStore {

    /**
     * 尝试抢占幂等键。
     * <p>
     * 语义等价于 Redis 的 {@code SETNX}：仅当键不存在时写入成功。
     *
     * @param key 幂等键（建议为 {tenantId}:{msgId}）
     * @param ttl 键的存活时长，避免无限增长
     * @return {@code true} 表示首次出现（获得处理权）；{@code false} 表示重复
     */
    boolean tryAcquire(String key, Duration ttl);

    /**
     * 主动释放幂等键（用于处理失败需允许重试的场景）。
     *
     * @param key 幂等键
     */
    default void release(String key) {
        // 默认不实现：多数场景依赖 TTL 自动过期
    }
}