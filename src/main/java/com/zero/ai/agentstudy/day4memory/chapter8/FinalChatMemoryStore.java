package com.zero.ai.agentstudy.day4memory.chapter8;

import org.springframework.stereotype.Repository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最终 Agent 短期 Chat Memory。
 */
@Repository
public class FinalChatMemoryStore {

    private static final int MAX_MESSAGES = 20;

    private final Map<String, Deque<FinalChatMessage>> store = new ConcurrentHashMap<>();

    public void append(String conversationId, FinalChatMessage message) {
        Deque<FinalChatMessage> messages = store.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(message);
            while (messages.size() > MAX_MESSAGES) {
                messages.removeFirst();
            }
        }
    }

    public List<FinalChatMessage> latest(String conversationId) {
        Deque<FinalChatMessage> messages = store.get(conversationId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}

