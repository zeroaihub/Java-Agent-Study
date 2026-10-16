package com.zero.ai.agentstudy.day14wechatagent.wechatsession.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.InboundMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.event.SessionExpiredEvent;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.model.ConversationSession;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.model.SessionStatus;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.port.SessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 会话应用服务（Application Service）。
 * <p>
 * 位于六边形架构的应用层，负责编排会话的生命周期用例：
 * <ul>
 *     <li>{@link #getOrCreate}：按入站消息定位或新建会话，并推进过期判定。</li>
 *     <li>{@link #touch}：记录一次交互并落库。</li>
 *     <li>{@link #sweepExpired}：过期扫描，清理并发布 {@link SessionExpiredEvent}。</li>
 * </ul>
 * 本服务不含任何持久化细节——一切通过 {@link SessionRepository} 端口完成，
 * 从而与内存 / Redis / JPA 实现解耦。
 *
 * @author ZeroAi
 */
@Slf4j
@Service
public class SessionService {

    private final SessionRepository repository;
    private final SessionProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public SessionService(SessionRepository repository,
                          SessionProperties properties,
                          ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 按入站消息获取会话；不存在则创建。
     * <p>
     * 获取后立即执行过期判定：若已被判定为 EXPIRED（长时间未活跃），
     * 视为一次「过期后又来消息」的场景，重建一个全新活跃会话，
     * 避免复用一段早已失去上下文意义的旧会话。
     *
     * @param message 入站消息
     * @return 可用（活跃或空闲）会话
     */
    public ConversationSession getOrCreate(InboundMessage message) {
        return getOrCreate(message.getTenantId(), message.getChannelType(),
                message.getConversationId());
    }

    /**
     * 按三元组获取会话；不存在则创建。
     *
     * @param tenantId       租户
     * @param channelType    渠道
     * @param conversationId 会话标识
     * @return 可用会话
     */
    public ConversationSession getOrCreate(String tenantId, ChannelType channelType,
                                           String conversationId) {
        ConversationSession session = repository
                .find(tenantId, channelType, conversationId)
                .orElse(null);

        if (session == null) {
            session = ConversationSession.create(tenantId, channelType, conversationId);
            repository.save(session);
            log.debug("[Session] 新建会话 key={}", session.sessionKey());
            return session;
        }

        // 命中旧会话，先推进过期判定
        boolean changed = session.evaluateExpiry(
                properties.getIdleThreshold(), properties.getExpireThreshold());
        if (session.getStatus() == SessionStatus.EXPIRED) {
            // 过期后又来消息：清理旧会话并重建
            publishExpired(session);
            repository.delete(session.sessionKey());
            session = ConversationSession.create(tenantId, channelType, conversationId);
            repository.save(session);
            log.debug("[Session] 旧会话已过期，重建 key={}", session.sessionKey());
        } else if (changed) {
            repository.save(session);
        }
        return session;
    }

    /**
     * 记录一次交互并落库。
     *
     * @param session 会话聚合根
     */
    public void touch(ConversationSession session) {
        session.touch();
        repository.save(session);
    }

    /**
     * 重置会话：删除旧会话并重建一个全新的活跃会话。
     * <p>
     * 用于 {@code /reset} 命令等「重开一局」的场景，清空多轮对话上下文，
     * 使后续对话从干净上下文开始。若旧会话存在则先删除，随后无论如何都会
     * 返回一个新建的活跃会话。
     *
     * @param tenantId       租户
     * @param channelType    渠道
     * @param conversationId 会话标识
     * @return 全新的活跃会话
     */
    public ConversationSession reset(String tenantId, ChannelType channelType,
                                     String conversationId) {
        String key = ConversationSession.sessionKey(tenantId, channelType, conversationId);
        repository.delete(key);
        ConversationSession fresh = ConversationSession.create(
                tenantId, channelType, conversationId);
        repository.save(fresh);
        log.debug("[Session] 会话已重置 key={}", fresh.sessionKey());
        return fresh;
    }

    /**
     * 过期扫描：遍历会话快照，推进过期判定，清理 EXPIRED 会话并发布事件。
     * <p>
     * 由定时任务（{@code SessionExpiryScheduler}）周期调用。仅内存实现
     * 提供 {@code snapshot()}；生产 Redis 实现可依赖其自带 TTL，无需本方法。
     *
     * @param sessions 待检查的会话集合快照
     * @return 本轮清理的过期会话数量
     */
    public int sweepExpired(Iterable<ConversationSession> sessions) {
        int expired = 0;
        for (ConversationSession session : sessions) {
            session.evaluateExpiry(
                    properties.getIdleThreshold(), properties.getExpireThreshold());
            if (session.getStatus() == SessionStatus.EXPIRED) {
                publishExpired(session);
                repository.delete(session.sessionKey());
                expired++;
            } else {
                repository.save(session);
            }
        }
        if (expired > 0) {
            log.info("[Session] 过期清理完成，本轮清理 {} 个会话", expired);
        }
        return expired;
    }

    private void publishExpired(ConversationSession session) {
        eventPublisher.publishEvent(SessionExpiredEvent.of(
                session.getTenantId(), session.getChannelType(),
                session.getConversationId(), session.sessionKey(),
                session.getTurnCount()));
    }
}