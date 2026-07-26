package com.zero.ai.agentstudy.day4memory.chapter1;

import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Day4 第一章 Memory Demo 服务。
 */
@Service
@RequiredArgsConstructor
public class Chapter1MemoryService {

    private static final String NO_MEMORY_MODE = "no-memory";
    private static final String WITH_MEMORY_MODE = "with-memory";

    private final AiService aiService;
    private final SimpleSessionChatMemory chatMemory;

    public Chapter1ChatResponse chatWithoutMemory(String message) {
        requireText(message, "message");

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(systemMessage("""
                你是 Day4 Memory 教学 Demo。
                当前接口不提供任何历史上下文。
                如果用户询问姓名、职业、之前说过什么等历史信息，而当前消息没有包含答案，
                你必须明确说明：当前请求没有记忆，无法知道之前的信息。
                """));
        messages.add(userMessage(message));

        String answer = aiService.chat(messages);
        return new Chapter1ChatResponse(
                NO_MEMORY_MODE,
                null,
                answer,
                messages.size(),
                List.of()
        );
    }

    public Chapter1ChatResponse chatWithMemory(String sessionId, String message) {
        requireText(sessionId, "sessionId");
        requireText(message, "message");

        List<ChatMessage> memoryMessages = chatMemory.getMessages(sessionId);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(systemMessage("""
                你是 Day4 Memory 教学 Demo。
                你会收到当前会话的历史消息。
                请只基于这些历史消息和用户当前输入回答，不要编造不存在的长期记忆。
                如果历史中出现用户姓名、职业、学习目标，请在回答中自然使用这些信息。
                """));
        messages.addAll(memoryMessages);
        messages.add(userMessage(message));

        String answer = aiService.chat(messages);

        chatMemory.append(sessionId, "user", message);
        chatMemory.append(sessionId, "assistant", answer);

        return new Chapter1ChatResponse(
                WITH_MEMORY_MODE,
                sessionId,
                answer,
                messages.size(),
                chatMemory.getMessages(sessionId)
        );
    }

    public List<ChatMessage> history(String sessionId) {
        requireText(sessionId, "sessionId");
        return chatMemory.getMessages(sessionId);
    }

    public void clear(String sessionId) {
        requireText(sessionId, "sessionId");
        chatMemory.clear(sessionId);
    }

    private ChatMessage systemMessage(String content) {
        return ChatMessage.builder()
                .role("system")
                .content(content)
                .build();
    }

    private ChatMessage userMessage(String content) {
        return ChatMessage.builder()
                .role("user")
                .content(content)
                .build();
    }

    private void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}

