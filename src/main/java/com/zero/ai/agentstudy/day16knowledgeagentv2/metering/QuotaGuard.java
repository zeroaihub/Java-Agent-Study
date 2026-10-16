package com.zero.ai.agentstudy.day16knowledgeagentv2.metering;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配额守卫（Chapter 16）：请求前置的「计量 + 限流」闸门。
 *
 * <p>两道检查，缺一不可：</p>
 * <ol>
 *     <li><b>日配额（计量）</b>：累计问答次数是否超过套餐上限；</li>
 *     <li><b>速率（限流）</b>：令牌桶控制瞬时并发，防止个别租户打爆共享资源。</li>
 * </ol>
 *
 * <p>超限抛 {@link AgentErrorCode#OPERATION_NOT_ALLOWED}（生产可细分为 QUOTA_EXCEEDED /
 * RATE_LIMITED 专用错误码）。演示用内存计数，生产应落 Redis 以支持多实例共享。</p>
 *
 * @author ZeroAi
 */
public class QuotaGuard {

    private final Quota quota;
    private final Map<String, AtomicLong> dailyUsed = new ConcurrentHashMap<>();
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public QuotaGuard(Quota quota) {
        this.quota = AgentException.requireNonNull(quota, "quota");
    }

    /**
     * 请求前置检查：通过则消耗一次配额与一个令牌，否则抛异常。
     */
    public void checkAndConsume(AgentContext ctx) {
        TenantId tenant = ctx.tenantId();
        String key = tenant.value();

        // 1) 速率限流（令牌桶）
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(quota.ratePerMinute()));
        AgentException.requireState(bucket.tryAcquire(),
                AgentErrorCode.OPERATION_NOT_ALLOWED,
                "请求过于频繁，已触发限流（租户 " + key + "）");

        // 2) 日配额计量
        AtomicLong used = dailyUsed.computeIfAbsent(key, k -> new AtomicLong());
        long after = used.incrementAndGet();
        if (after > quota.dailyQueryLimit()) {
            used.decrementAndGet();
            throw new AgentException(AgentErrorCode.OPERATION_NOT_ALLOWED,
                    "已达每日配额上限 " + quota.dailyQueryLimit() + "（租户 " + key + "）");
        }
    }

    public long usedToday(TenantId tenant) {
        AtomicLong used = dailyUsed.get(tenant.value());
        return used == null ? 0 : used.get();
    }

    /**
     * 极简令牌桶：容量 = 每分钟速率，按毫秒匀速补充。
     */
    private static final class TokenBucket {

        private final long capacity;
        private final double refillPerMs;
        private double tokens;
        private long lastRefill = System.currentTimeMillis();

        TokenBucket(long ratePerMinute) {
            this.capacity = ratePerMinute;
            this.refillPerMs = ratePerMinute / 60_000.0;
            this.tokens = ratePerMinute;
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerMs);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}