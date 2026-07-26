package com.zero.ai.agentstudy.day4memory.chapter8;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 第八章：完成 Memory Agent。
 */
@Service
@RequiredArgsConstructor
public class FinalMemoryAgentService {

    private final FinalProfileRepository profileRepository;
    private final FinalChatMemoryStore chatMemoryStore;
    private final FinalProfileExtractor profileExtractor;

    public FinalMemoryChatResponse chat(FinalMemoryChatRequest request) {
        requireText(request.userId(), "userId");
        requireText(request.sessionId(), "sessionId");
        requireText(request.message(), "message");

        String conversationId = conversationId(request.userId(), request.sessionId());
        FinalUserProfile profile = profileRepository.findByUserId(request.userId())
                .orElseGet(() -> FinalUserProfile.empty(request.userId()));

        profileExtractor.updateProfile(profile, request.message());
        profileRepository.save(profile);

        List<FinalChatMessage> recentMessages = chatMemoryStore.latest(conversationId);
        String prompt = buildPrompt(profile, recentMessages, request.message());
        String answer = generatePersonalizedAnswer(profile, request.message());

        chatMemoryStore.append(conversationId, FinalChatMessage.of("user", request.message()));
        chatMemoryStore.append(conversationId, FinalChatMessage.of("assistant", answer));

        return new FinalMemoryChatResponse(
                conversationId,
                answer,
                profile,
                chatMemoryStore.latest(conversationId),
                prompt,
                architectureReview()
        );
    }

    public FinalMemoryChatResponse inspect(String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        String conversationId = conversationId(userId, sessionId);
        FinalUserProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> FinalUserProfile.empty(userId));
        List<FinalChatMessage> recentMessages = chatMemoryStore.latest(conversationId);
        return new FinalMemoryChatResponse(
                conversationId,
                "",
                profile,
                recentMessages,
                buildPrompt(profile, recentMessages, ""),
                architectureReview()
        );
    }

    public void clearSession(String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        chatMemoryStore.clear(conversationId(userId, sessionId));
    }

    private String generatePersonalizedAnswer(FinalUserProfile profile, String message) {
        String name = StringUtils.hasText(profile.getName()) ? profile.getName() : "同学";
        String profession = StringUtils.hasText(profile.getProfession()) ? profile.getProfession() : "当前职业未记录";
        String goal = profile.getLearningGoals().isEmpty()
                ? "当前学习目标未记录"
                : String.join("、", profile.getLearningGoals());

        if (message.contains("我是谁") || message.contains("记住了什么")) {
            return """
                    %s，我当前记住的信息是：
                    - 职业：%s
                    - 学习目标：%s
                    - 回答偏好：%s
                    """.formatted(name, profession, goal, profile.getPreferences());
        }

        String style = profile.getPreferences().contains("Java 优先")
                ? "我会优先用 Java、Spring Boot 和企业分层架构来解释。"
                : "我会按通用 Agent 架构来解释。";

        return """
                %s，已结合你的长期记忆回答。
                你的职业画像：%s。
                你的学习目标：%s。
                %s
                当前问题：%s
                """.formatted(name, profession, goal, style, message);
    }

    private String buildPrompt(FinalUserProfile profile, List<FinalChatMessage> recentMessages, String question) {
        return """
                你是最终版 Memory Agent。

                【长期记忆 UserProfile】
                %s

                【短期记忆 Recent Chat】
                %s

                【当前问题】
                %s

                要求：根据长期记忆调整回答内容；不要编造未保存的用户信息。
                """.formatted(profile.promptText(), recentMessages, question);
    }

    private List<String> architectureReview() {
        return List.of(
                "包路径独立：chapter8 不影响前面学习代码。",
                "分层清晰：Controller -> Service -> MemoryStore/ProfileRepository/Extractor。",
                "短期记忆与长期画像分离，方便后续替换 Redis 和 MySQL。",
                "userId + sessionId 做会话隔离，避免串话。",
                "已预留 promptPreview，后续接入 RAG 时可增加 ragContext 分区。",
                "生产优化方向：增加真实 LLM 结构化抽取、置信度、用户确认、持久化、测试覆盖。"
        );
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

