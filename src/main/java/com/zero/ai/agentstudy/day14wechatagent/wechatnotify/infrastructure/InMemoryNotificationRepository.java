package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.Notification;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.NotifyStatus;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.port.NotificationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版通知仓储实现（教学用）。
 *
 * <p>用 {@link ConcurrentHashMap}存储通知，另用一张 {@code dedupeIndex} 维护
 * 幂等键 -> 通知 ID 的索引，支持 {@code findByDedupeKey} 快速去重查询。</p>
 *
 * <p><strong>生产提示：</strong>内存实现进程重启即丢失，尤其死信与待重投通知会永久
 * 消失。生产必须替换为 PostgreSQL/Redis 持久化实现（见教程避坑）。因为通知的全部状态
 * 都在 {@link Notification} 聚合根里，替换仓储即可无缝迁移。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Repository
public class InMemoryNotificationRepository implements NotificationRepository {

    /** id -> 通知。 */
    private final Map<String, Notification> store = new ConcurrentHashMap<>();

    /** dedupeKey -> id，幂等索引。 */
    private final Map<String, String> dedupeIndex = new ConcurrentHashMap<>();

    @Override
    public Notification save(Notification notification) {
        store.put(notification.id(), notification);
        if (notification.dedupeKey() != null) {
            dedupeIndex.put(notification.dedupeKey(), notification.id());
        }
        return notification;
    }

    @Override
    public Optional<Notification> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<Notification> findByDedupeKey(String dedupeKey) {
        String id = dedupeIndex.get(dedupeKey);
        return id == null ? Optional.empty() : findById(id);
    }

    @Override
    public List<Notification> findDeadLetters(String tenantId) {
        return store.values().stream()
                .filter(n -> n.tenantId().equals(tenantId))
                .filter(n -> n.status() == NotifyStatus.DEAD)
                .collect(Collectors.toList());
    }
}