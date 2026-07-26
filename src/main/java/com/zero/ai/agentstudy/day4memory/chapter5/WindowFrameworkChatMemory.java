package com.zero.ai.agentstudy.day4memory.chapter5;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 窗口式 ChatMemory。
 *
 * 类似 Spring AI MessageWindowChatMemory / LangChain4j MessageWindowChatMemory 的教学版实现。
 */
@Component
public class WindowFrameworkChatMemory implements FrameworkChatMemory {

    private static final int MAX_MESSAGES = 10;

    private final Map<String, Deque<FrameworkMessage>> conversations = new ConcurrentHashMap<>();

    @Override
    public void add(String conversationId, FrameworkMessage message) {
        Deque<FrameworkMessage> queue = conversations.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(message);
            while (queue.size() > MAX_MESSAGES) {
                queue.removeFirst();
            }
        }
    }

    @Override
    public List<FrameworkMessage> get(String conversationId) {
        Deque<FrameworkMessage> queue = conversations.get(conversationId);
        if (queue == null) {
            return List.of();
        }
        synchronized (queue) {
            return new ArrayList<>(queue);
        }
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }
}

