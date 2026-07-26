package com.zero.ai.agentstudy.day4memory.chapter1;

import com.zero.ai.agentstudy.back.model.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第一章教学版 Chat Memory。
 *
 * 重点演示两个概念：
 * 1. Memory 是应用层保存的上下文，不是 LLM 自带能力。
 * 2. sessionId 必须隔离，否则会出现用户信息串线。
 *
 * 生产环境建议替换为 Redis / JDBC / Spring AI ChatMemoryRepository。
 */
@Component
public class SimpleSessionChatMemory {

    private static final int MAX_MESSAGES = 10;

    private final Map<String, Deque<ChatMessage>> sessions = new ConcurrentHashMap<>();

    public void append(String sessionId, String role, String content) {
        Deque<ChatMessage> messages = sessions.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(ChatMessage.builder()
                    .role(role)
                    .content(content)
                    .build());
            while (messages.size() > MAX_MESSAGES) {
                messages.removeFirst();
            }
        }
    }

    public List<ChatMessage> getMessages(String sessionId) {
        Deque<ChatMessage> messages = sessions.get(sessionId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }
}

