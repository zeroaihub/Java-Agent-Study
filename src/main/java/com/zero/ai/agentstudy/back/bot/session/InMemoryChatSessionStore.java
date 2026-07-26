package com.zero.ai.agentstudy.back.bot.session;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话存储实现
 *
 * 适用场景: 单机开发/测试/Demo
 * 注意: 重启后丢失; 不支持多实例(多实例会话不一致)
 *
 * 生产环境替换为 RedisChatSessionStore:
 *   - 用 Redis Hash 存 session 字段
 *   - 用 Redis List 存 messages
 *   - 设置 TTL(如2小时)自动过期
 *
 * @author ZeroAi
 */
@Component
public class InMemoryChatSessionStore implements ChatSessionStore {

    /** 线程安全的 Map */
    private final Map<String, ChatSession> store = new ConcurrentHashMap<>();

    @Override
    public ChatSession getOrCreate(String sessionId, String systemPrompt) {
        return store.computeIfAbsent(sessionId, id -> new ChatSession(id, systemPrompt));
    }

    @Override
    public Optional<ChatSession> get(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    @Override
    public void save(ChatSession session) {
        store.put(session.getSessionId(), session);
    }

    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }

    @Override
    public boolean exists(String sessionId) {
        return store.containsKey(sessionId);
    }
}
