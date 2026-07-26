package com.zero.ai.agentstudy.back.bot.session;

import java.util.Optional;

/**
 * 会话存储接口
 *
 * 通过接口抽象, 可以灵活切换实现:
 *   - InMemoryChatSessionStore: 开发/测试用
 *   - RedisChatSessionStore:    生产环境用
 *   - JdbcChatSessionStore:     需要持久化审计时用
 *
 * @author ZeroAi
 */
public interface ChatSessionStore {

    /** 创建或获取会话(若已存在则返回已存在的) */
    ChatSession getOrCreate(String sessionId, String systemPrompt);

    /** 获取会话 */
    Optional<ChatSession> get(String sessionId);

    /** 保存/更新会话 */
    void save(ChatSession session);

    /** 清空会话 */
    void clear(String sessionId);

    /** 判断会话是否存在 */
    boolean exists(String sessionId);
}
