package com.zero.ai.agentstudy.day4memory.chapter6;

import java.util.List;

/**
 * 短期聊天记忆存储抽象。
 *
 * 生产环境可用 Redis List 实现。
 */
public interface ChatMemoryStore {

    void append(String conversationId, StoredChatMessage message);

    List<StoredChatMessage> latest(String conversationId, int limit);

    void clear(String conversationId);
}

