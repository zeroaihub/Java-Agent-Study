package com.zero.ai.agentstudy.day14wechatagent.wechatsession.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.model.ConversationSession;
import com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.port.SessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话仓储的内存实现（教学 / 单机默认）。
 * <p>
 * 使用 {@link ConcurrentHashMap} 以会话身份键为索引存储会话聚合根，
 * 保证并发安全。生产环境应提供 Redis 实现（带 TTL），通过
 * {@link ConditionalOnMissingBean} 让位——只要容器中存在名为
 * {@code redisSessionRepository} 的 Bean，本实现自动退场。
 *
 * <p><b>注意：</b>内存实现随进程重启丢失会话，仅适用于本地联调与教学。
 *
 * @author ZeroAi
 */
@Repository
@ConditionalOnMissingBean(name = "redisSessionRepository")
public class InMemorySessionRepository implements SessionRepository {

    /** 会话身份键 -> 会话聚合根。 */
    private final ConcurrentMap<String, ConversationSession> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ConversationSession> find(String tenantId, ChannelType channelType,
                                              String conversationId) {
        String key = ConversationSession.sessionKey(tenantId, channelType, conversationId);
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void save(ConversationSession session) {
        store.put(session.sessionKey(), session);
    }

    @Override
    public void delete(String sessionKey) {
        store.remove(sessionKey);
    }

    /**
     * 返回当前存活会话的快照（供过期扫描 / 监控使用）。
     * <p>
     * 返回不可变副本，避免调用方在遍历时被并发修改干扰。
     *
     * @return 会话列表快照
     */
    public List<ConversationSession> snapshot() {
        return List.copyOf(store.values());
    }

    /**
     * 当前会话总数（监控指标）。
     *
     * @return 会话数量
     */
    public int size() {
        return store.size();
    }
}