package com.zero.ai.agentstudy.day4memory.chapter6;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 第六章：Java 实现 Memory Agent。
 */
@Service
@RequiredArgsConstructor
public class Chapter6MemoryAgentService {

    private static final int RECENT_MESSAGES_LIMIT = 20;

    private final ChatMemoryStore chatMemoryStore;
    private final UserProfileRepository userProfileRepository;
    private final ProfileSignalExtractor profileSignalExtractor;

    public MemoryChatResponse chat(MemoryChatRequest request) {
        requireText(request.userId(), "userId");
        requireText(request.sessionId(), "sessionId");
        requireText(request.message(), "message");

        String conversationId = conversationId(request.userId(), request.sessionId());
        Chapter6UserProfile profile = userProfileRepository.findByUserId(request.userId())
                .orElseGet(() -> Chapter6UserProfile.empty(request.userId()));

        profileSignalExtractor.updateProfile(profile, request.message());
        userProfileRepository.save(profile);

        List<StoredChatMessage> recentMessages = chatMemoryStore.latest(conversationId, RECENT_MESSAGES_LIMIT);
        String prompt = buildPrompt(profile, recentMessages, request.message());
        String answer = mockAnswer(profile, request.message());

        chatMemoryStore.append(conversationId, StoredChatMessage.of("user", request.message()));
        chatMemoryStore.append(conversationId, StoredChatMessage.of("assistant", answer));

        List<StoredChatMessage> afterMessages = chatMemoryStore.latest(conversationId, RECENT_MESSAGES_LIMIT);
        return new MemoryChatResponse(
                conversationId,
                answer,
                afterMessages.size(),
                profile,
                afterMessages,
                prompt
        );
    }

    public MemoryChatResponse history(String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        String conversationId = conversationId(userId, sessionId);
        Chapter6UserProfile profile = userProfileRepository.findByUserId(userId)
                .orElseGet(() -> Chapter6UserProfile.empty(userId));
        List<StoredChatMessage> recentMessages = chatMemoryStore.latest(conversationId, RECENT_MESSAGES_LIMIT);
        return new MemoryChatResponse(
                conversationId,
                "",
                recentMessages.size(),
                profile,
                recentMessages,
                buildPrompt(profile, recentMessages, "")
        );
    }

    public void clear(String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        chatMemoryStore.clear(conversationId(userId, sessionId));
    }

    private String buildPrompt(Chapter6UserProfile profile, List<StoredChatMessage> recentMessages, String message) {
        return """
                你是企业级 Memory Agent。

                【长期用户画像 MySQL】
                %s

                【最近 10 轮聊天 Redis】
                %s

                【用户当前输入】
                %s
                """.formatted(profile.promptText(), recentMessages, message);
    }

    private String mockAnswer(Chapter6UserProfile profile, String message) {
        String name = StringUtils.hasText(profile.getName()) ? profile.getName() : "同学";
        String style = profile.getPreferences().contains("Java 优先") ? "我会优先使用 Java/Spring Boot 视角。" : "";
        return name + "，我已结合你的长期画像和最近 10 轮上下文处理这个问题。" + style
                + " 当前消息：" + message;
    }

    private String conversationId(String userId, String sessionId) {
        return userId + ":" + sessionId;
    }

    private void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}

