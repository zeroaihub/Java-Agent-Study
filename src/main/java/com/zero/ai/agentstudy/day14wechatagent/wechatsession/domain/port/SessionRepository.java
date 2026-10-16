package com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.model.ConversationSession;

import java.util.Optional;

/**
 * 出站端口：会话仓储（Repository 模式）。
 * <p>
 * 领域层通过该端口持久化 / 检索 {@link ConversationSession} 聚合根，
 * 不感知底层是内存、Redis 还是 PostgreSQL。教学环境提供内存实现，
 * 生产环境可切 Redis（带 TTL，天然贴合会话过期语义）。
 *
 * @author ZeroAi
 */
public interface SessionRepository {

    /**
     * 按会话身份查找。
     *
     * @param tenantId       租户
     * @param channelType    渠道
     * @param conversationId 会话标识
     * @return 会话（可能不存在）
     */
    Optional<ConversationSession> find(String tenantId, ChannelType channelType,
                                       String conversationId);

    /**
     * 保存（新增或更新）会话。
     *
     * @param session 会话聚合根
     */
    void save(ConversationSession session);

    /**
     * 删除会话（过期清理时使用）。
     *
     * @param sessionKey 会话身份键
     */
    void delete(String sessionKey);
}