package com.zero.ai.agentstudy.day4memory.chapter6;

import org.springframework.stereotype.Repository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis 风格的短期 Memory 存储。
 *
 * 教学版使用内存 Deque 模拟 Redis List：
 * - RPUSH：append
 * - LRANGE：latest
 * - LTRIM：保留最近 20 条，也就是最近 10 轮
 */
@Repository
public class RedisLikeChatMemoryStore implements ChatMemoryStore {

    private static final int MAX_MESSAGES = 20;

    private final Map<String, Deque<StoredChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void append(String conversationId, StoredChatMessage message) {
        Deque<StoredChatMessage> messages = store.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(message);
            while (messages.size() > MAX_MESSAGES) {
                messages.removeFirst();
            }
        }
    }

    @Override
    public List<StoredChatMessage> latest(String conversationId, int limit) {
        Deque<StoredChatMessage> messages = store.get(conversationId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            List<StoredChatMessage> all = new ArrayList<>(messages);
            if (all.size() <= limit) {
                return all;
            }
            return all.subList(all.size() - limit, all.size());
        }
    }

    @Override
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}

