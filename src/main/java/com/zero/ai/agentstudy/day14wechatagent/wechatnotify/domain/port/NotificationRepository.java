package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.Notification;

import java.util.List;
import java.util.Optional;

/**
 * 通知仓储端口（领域端口）。
 *
 * <p>负责通知的持久化与查询。通知状态需跨投递周期保存——尤其是失败重投、死信排查
 * 都依赖持久化的状态。本项目提供内存实现，生产可替换为 PostgreSQL/Redis 实现。</p>
 *
 * <p>本端口<strong>不依赖任何 Spring 注解</strong>；实现类在 infrastructure 层用
 * {@code @Repository} 声明。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public interface NotificationRepository {

    /**
     * 保存或更新通知。
     *
     * @param notification 通知
     * @return 保存后的通知
     */
    Notification save(Notification notification);

    /**
     * 按 ID 查询。
     *
     * @param id 通知 ID
     * @return 通知 Optional
     */
    Optional<Notification> findById(String id);

    /**
     * 按幂等键查询（用于投递去重）。
     *
     * @param dedupeKey 幂等键
     * @return 通知 Optional
     */
    Optional<Notification> findByDedupeKey(String dedupeKey);

    /**
     * 查询指定租户下所有死信通知（供人工处理）。
     *
     * @param tenantId 租户 ID
     * @return 死信通知列表
     */
    List<Notification> findDeadLetters(String tenantId);
}