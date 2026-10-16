package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.Notification;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.NotifyChannel;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.SendResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.port.NotificationRepository;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.port.NotifySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 通知中心（application 层核心）。
 *
 * <p>它是企业级「消息可靠送达」的统一出口，把 {@link NotifySender}（多渠道发送）与
 * {@link NotificationRepository}（状态持久化）编织成一条带「去重 → 路由 → 投递 → 重试 → 死信」
 * 的健壮投递链路。</p>
 *
 * <p><strong>投递流程：</strong></p>
 * <pre>
 *   send(notification)
 *       |
 *       +-- 幂等去重：dedupeKey 已存在且已 SENT -> 直接返回，不重复投递
 *       |
 *       +-- 路由：按 channel 找到匹配的 NotifySender
 *       |         找不到 -> markDead（无渠道可投）
 *       |
 *       +-- 投递循环（最多 maxRetries+1 次）：
 *       |         markSending -> sender.send
 *       |             +-- ok   -> markSent，结束
 *       |             +-- fail -> markFailed（可重试则回 PENDING 继续循环，否则 DEAD）
 *       |
 *       +-- 每次状态流转后 save
 * </pre>
 *
 * @author ZeroHub AI Agent Platform
 */
@Service
public class NotificationCenter {

    private static final Logger log = LoggerFactory.getLogger(NotificationCenter.class);

    private final List<NotifySender> senders;
    private final NotificationRepository repository;

    /**
     * 构造通知中心（Spring 自动注入所有 NotifySender 实现）。
     *
     * @param senders    所有渠道发送器（策略集合）
     * @param repository 通知仓储
     */
    public NotificationCenter(List<NotifySender> senders, NotificationRepository repository) {
        this.senders = senders;
        this.repository = repository;
    }

    /**
     * 发送通知（同步投递，含去重、重试、死信兜底）。
     *
     * @param notification 待投递通知
     * @return 投递后的通知（状态为 SENT 或 DEAD）
     */
    public Notification send(Notification notification) {
        // 1) 幂等去重：带幂等键且已成功投递过，直接返回历史结果
        if (notification.dedupeKey() != null) {
            Optional<Notification> existing = repository.findByDedupeKey(notification.dedupeKey());
            if (existing.isPresent() && existing.get().status().isTerminal()) {
              log.info("[Notify] 幂等命中 dedupeKey={} 已存在(status={})，跳过重复投递",
                        notification.dedupeKey(), existing.get().status());
                return existing.get();
            }
        }
        repository.save(notification);

        // 2) 路由：找到支持该渠道的发送器
        Optional<NotifySender> sender = findSender(notification.channel());
        if (sender.isEmpty()) {
            notification.markDead("无支持渠道的发送器: " + notification.channel());
            repository.save(notification);
            log.error("[Notify] id={} 无渠道发送器 channel={}", notification.id(), notification.channel());
            return notification;
        }

        // 3) 投递循环（重试直到成功、死信或耗尽）
        while (notification.canRetry()) {
            notification.markSending();
            log.info("[Notify] id={} 第 {} 次投递 channel={} target={}",
                    notification.id(), notification.attempts(), notification.channel(), notification.target());

            SendResult result;
            try {
                result = sender.get().send(notification);
            } catch (Exception ex) {
                result = SendResult.fail("发送器抛出异常: " + ex.getMessage());
                log.error("[Notify] id={} 发送异常", notification.id(), ex);
            }

            if (result.success()) {
                notification.markSent();
                repository.save(notification);
           log.info("[Notify] id={} 投递成功 messageId={}", notification.id(), result.messageId());
                return notification;
            }

            notification.markFailed(result.error());
            repository.save(notification);
            log.warn("[Notify] id={} 第 {} 次投递失败: {} (status={})",
                    notification.id(), notification.attempts(), result.error(), notification.status());

            if (notification.status().isTerminal()) {
                break; // 已进入 DEAD
            }
        }

        log.error("[Notify] id={} 重试耗尽，进入死信 status={}", notification.id(), notification.status());
        return notification;
    }

    /**
     * 便捷方法：发送企业微信 Markdown 通知。
     *
     * @param tenantId 租户 ID
     * @param target   接收方（userId 或 chatId）
     * @param title    标题
     * @param content  Markdown 正文
     * @return 投递后的通知
     */
    public Notification sendWechatMarkdown(String tenantId, String target, String title, String content) {
        return send(Notification.wechatMarkdown(tenantId, target, title, content));
    }

    /**
     * 重投一条死信通知（人工介入后重试）。
     *
     * @param notificationId 通知 ID
     * @return 重投后的通知
     */
    public Notification redeliver(String notificationId) {
        Notification n = repository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("通知不存在: " + notificationId));
        if (!n.status().isTerminal()) {
            log.info("[Notify] id={} 非终态，无需重投", notificationId);
            return n;
        }
        // 死信重投：构造一条同内容的新通知（保留幂等键便于追踪）
        Notification retry = new Notification(n.tenantId(), n.channel(), n.target(),
                n.title(), n.content(), n.markdown(), n.dedupeKey(), n.maxRetries());
        log.info("[Notify] 死信重投 原id={} 新id={}", notificationId, retry.id());
        return send(retry);
    }

    /**
     * 查询租户死信列表。
     *
     * @param tenantId 租户 ID
     * @return 死信通知列表
     */
    public List<Notification> deadLetters(String tenantId) {
        return repository.findDeadLetters(tenantId);
    }

    /**
     * 按渠道查找第一个支持的发送器。
     *
     * @param channel 渠道
     * @return 发送器 Optional
     */
    private Optional<NotifySender> findSender(NotifyChannel channel) {
        return senders.stream().filter(s -> s.supports(channel)).findFirst();
    }
}