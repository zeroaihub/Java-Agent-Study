package com.zero.ai.agentstudy.day4memory.chapter5;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 第五章：Spring AI 与 LangChain4j 中的 Memory 设计思想。
 */
@Service
@RequiredArgsConstructor
public class Chapter5FrameworkMemoryService {

    private final FrameworkChatMemory chatMemory;

    public FrameworkDesignResponse design() {
        return new FrameworkDesignResponse(
                asciiDiagram(),
                List.of(
                        "ChatMemory：对话记忆抽象",
                        "ChatMemoryRepository：底层存储抽象",
                        "MessageWindowChatMemory：窗口式聊天记忆",
                        "conversationId：隔离不同会话"
                ),
                List.of(
                        "ChatMemory：消息容器",
                        "MessageWindowChatMemory：按消息数量保留窗口",
                        "TokenWindowChatMemory：按 token 控制窗口",
                        "memoryId：隔离用户或会话"
                ),
                List.of(
                        "业务代码依赖 Memory 抽象，不依赖存储细节",
                        "通过 conversationId/sessionId 做会话隔离",
                        "生产环境可替换为 Redis、JDBC、向量库等实现",
                        "ChatMemory 负责短期上下文，UserProfile 负责长期画像"
                )
        );
    }

    public SessionMemoryResponse chat(SessionRequest request) {
        requireText(request.userId(), "userId");
        requireText(request.sessionId(), "sessionId");
        requireText(request.message(), "message");

        ConversationKey key = new ConversationKey(request.userId(), request.sessionId());
        String conversationId = key.conversationId();

        chatMemory.add(conversationId, FrameworkMessage.of("user", request.message()));
        chatMemory.add(conversationId, FrameworkMessage.of("assistant",
                "教学模拟回答：本轮消息已写入 ChatMemory，conversationId=" + conversationId));

        List<FrameworkMessage> messages = chatMemory.get(conversationId);
        return new SessionMemoryResponse(
                conversationId,
                messages.size(),
                messages,
                "userId + sessionId 组成 conversationId，框架用它隔离不同会话的 ChatMemory。"
        );
    }

    public SessionMemoryResponse history(String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        String conversationId = new ConversationKey(userId, sessionId).conversationId();
        List<FrameworkMessage> messages = chatMemory.get(conversationId);
        return new SessionMemoryResponse(
                conversationId,
                messages.size(),
                messages,
                "这是当前 conversationId 下的窗口式短期记忆。"
        );
    }

    public void clear(String userId, String sessionId) {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        chatMemory.clear(new ConversationKey(userId, sessionId).conversationId());
    }

    private void requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }

    private String asciiDiagram() {
        return """
                Controller
                   |
                   v
                ConversationKey(userId, sessionId)
                   |
                   v
                ChatMemory 抽象
                   |
                   +--> InMemory 实现
                   +--> Redis 实现
                   +--> JDBC 实现
                   +--> Vector/Hybrid 实现
                """;
    }
}

