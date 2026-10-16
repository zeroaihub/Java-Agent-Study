package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.ConversationMemory;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.MemoryMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port.ConversationMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 短期记忆的内存实现（适配器）。
 *
 * <p>用 {@link ConcurrentHashMap} 以 {@code memoryKey -> ConversationMemory} 存储会话记忆，
 * 满足单机教学与测试。生产环境应替换为 Redis 适配器（带 TTL 过期，与会话生命周期对齐），
 * 只要实现同一个 {@link ConversationMemoryStore} 端口，应用层无需改动。</p>
 *
 * <p>注意：{@code append} 通过 {@link ConcurrentHashMap#compute} 保证「读改写」的原子性，
 * 避免并发追加时丢消息。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Repository
public class InMemoryConversationMemoryStore implements ConversationMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryConversationMemoryStore.class);

    private final ConcurrentHashMap<String, ConversationMemory> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ConversationMemory> load(String memoryKey) {
        return Optional.ofNullable(store.get(memoryKey));
    }

    @Override
    public ConversationMemory append(String memoryKey, MemoryMessage message) {
        return store.compute(memoryKey, (key, existing) -> {
            ConversationMemory memory = existing != null
                    ? existing
                    : ConversationMemory.empty(key);
            memory.append(message);
            return memory;
        });
    }

    @Override
    public void save(ConversationMemory memory) {
        store.put(memory.memoryKey(), memory);
    }

    @Override
    public void clear(String memoryKey) {
        ConversationMemory removed = store.remove(memoryKey);
        if (removed != null) {
            log.debug("清空短期记忆: key={}, size={}", memoryKey, removed.size());
        }
    }
}