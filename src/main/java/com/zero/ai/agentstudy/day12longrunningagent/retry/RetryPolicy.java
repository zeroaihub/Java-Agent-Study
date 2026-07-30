package com.zero.ai.agentstudy.day12longrunningagent.retry;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 重试策略（指数退避 + 抖动）。
 *
 * <p>失败重试若采用固定间隔，会在高并发下形成"重试风暴"（大量请求在同一时刻齐发，
 * 反复冲击已经不堪重负的下游）。业界标准做法是 <b>指数退避（Exponential Backoff）</b>：
 * 每次重试的等待时间指数增长；再叠加 <b>抖动（Jitter）</b> 打散并发峰值。</p>
 *
 * <p>公式：{@code delay = min(baseDelay * 2^(attempt-1), maxDelay) + random(0, jitter)}</p>
 */
public class RetryPolicy {

    /** 最大重试次数（不含首次执行）。 */
    private final int maxRetries;

    /** 基础退避（毫秒）。 */
    private final long baseDelayMillis;

    /** 退避上限（毫秒），防止指数爆炸导致等待过久。 */
    private final long maxDelayMillis;

    /** 抖动上限（毫秒），实际抖动在 [0, jitter) 间随机。 */
    private final long jitterMillis;

    public RetryPolicy(int maxRetries, long baseDelayMillis, long maxDelayMillis, long jitterMillis) {
        this.maxRetries = maxRetries;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.jitterMillis = jitterMillis;
    }

    /** 一组适合大多数场景的默认参数：最多 3 次，基础 1s，上限 30s，抖动 1s。 */
    public static RetryPolicy defaultPolicy() {
        return new RetryPolicy(3, 1000L, 30_000L, 1000L);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /** 是否还允许再重试（attempt 为"已尝试重试次数"，从 1 开始）。 */
    public boolean canRetry(int attempt) {
        return attempt <= maxRetries;
    }

    /**
     * 计算第 attempt 次重试前应等待的毫秒数。
     *
     * @param attempt 第几次重试（从 1 开始）
     */
    public long nextDelayMillis(int attempt) {
        if (attempt < 1) {
            attempt = 1;
        }
        // 2^(attempt-1)，用位移实现，注意防止溢出
        long exp = attempt - 1 >= 62 ? Long.MAX_VALUE : (1L << (attempt - 1));
        long backoff;
        // 溢出保护：exp * baseDelay 可能溢出
        if (exp > maxDelayMillis / Math.max(baseDelayMillis, 1)) {
            backoff = maxDelayMillis;
        } else {
            backoff = Math.min(baseDelayMillis * exp, maxDelayMillis);
        }
        long jitter = jitterMillis <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterMillis);
        return backoff + jitter;
    }

    @Override
    public String toString() {
        return "RetryPolicy{maxRetries=" + maxRetries
                + ", baseDelayMillis=" + baseDelayMillis
                + ", maxDelayMillis=" + maxDelayMillis
             + ", jitterMillis=" + jitterMillis + "}";
    }
}