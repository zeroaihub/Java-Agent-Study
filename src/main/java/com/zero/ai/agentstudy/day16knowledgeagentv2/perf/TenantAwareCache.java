package com.zero.ai.agentstudy.day16knowledgeagentv2.perf;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.TenantId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 租户感知缓存（Chapter 17）。
 *
 * <p>缓存是延迟优化的第二大杀器，但在 SaaS 里有个致命陷阱：<b>缓存串租户</b>——
 * A 租户的答案被 B 租户命中，等于数据泄露。本缓存强制所有 key 带租户前缀，从根上杜绝
 * 跨租户命中，是「性能」与「隔离」的结合点。</p>
 *
 * <p>演示用 ConcurrentHashMap，生产用 Redis（key 同样带租户前缀 + TTL）。</p>
 *
 * @param <V> 缓存值类型
 * @author ZeroAi
 */
public class TenantAwareCache<V> {

    private final Map<String, V> store = new ConcurrentHashMap<>();
    private long hits;
    private long misses;

    /**
     * 读缓存；未命中则用 loader 计算并回填。返回值一定属于当前租户。
     *
     * @param ctx    请求上下文（提供租户前缀）
     * @param rawKey 业务 key（如查询归一化文本）
     * @param loader 未命中时的加载逻辑
     * @return 缓存或新加载的值
     */
    public V get(AgentContext ctx, String rawKey, Supplier<V> loader) {
        String key = scopedKey(ctx, rawKey);
        V cached = store.get(key);
        if (cached != null) {
            hits++;
            return cached;
        }
        misses++;
        V value = loader.get();
        if (value != null) {
            store.put(key, value);
        }
        return value;
    }

    private String scopedKey(AgentContext ctx, String rawKey) {
        TenantId tenant = ctx.tenantId();
        AgentException.requireText(rawKey, "rawKey");
        return tenant.value() + "::" + rawKey;
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0 : (double) hits / total;
    }
}