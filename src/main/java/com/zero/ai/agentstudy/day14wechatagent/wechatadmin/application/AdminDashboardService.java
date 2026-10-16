package com.zero.ai.agentstudy.day14wechatagent.wechatadmin.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.application.NotificationCenter;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理后台看板应用服务（wechatadmin 模块）。
 *
 * <p>管理后台是「运营/运维视角」的入口，与面向业务调用方的 API 视角不同：
 * 它关心的是<strong>平台运行健康度</strong>——尤其是死信队列这种「需要人来兜底」的数据。</p>
 *
 * <p>本服务是应用层编排者，聚合底层能力（当前聚焦通知死信管理），
 * 为管理后台 Controller 提供「看板数据」与「运维操作」两类能力：</p>
 * <ul>
 *     <li><strong>看板数据</strong>：死信列表、死信数量统计。</li>
 *     <li><strong>运维操作</strong>：人工重投死信。</li>
 * </ul>
 *
 * <p><strong>为什么 admin 独立成模块？</strong>后台的权限模型、审计要求、
 * 数据视角都与对外 API 不同。独立模块便于施加不同的安全策略（如后台需管理员权限），
 * 也符合 Spring Modulith「按业务能力划分模块」的思想。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Service
public class AdminDashboardService {

    private static final Logger log = LoggerFactory.getLogger(AdminDashboardService.class);

    private final NotificationCenter notificationCenter;

    /**
     * 构造函数注入通知中心。
     *
     * @param notificationCenter 通知中心
     */
    public AdminDashboardService(NotificationCenter notificationCenter) {
        this.notificationCenter = notificationCenter;
    }

    /**
     * 查询某租户的死信队列概览。
     *
     * @param tenantId 租户 ID
     * @return 死信概览（数量 + 明细）
     */
    public DeadLetterOverview deadLetterOverview(String tenantId) {
        List<Notification> deadLetters = notificationCenter.deadLetters(tenantId);
        List<DeadLetterItem> items = deadLetters.stream()
                .map(n -> new DeadLetterItem(n.id(), n.channel().name(),
                        n.target(), n.attempts(), n.lastError()))
                .toList();
        log.info("[Admin] 租户 {} 死信数量={}", tenantId, items.size());
        return new DeadLetterOverview(tenantId, items.size(), items);
    }

    /**
     * 管理员人工重投一条死信。
     *
     * @param notificationId 死信通知 ID
     * @return 重投后通知的最新状态
     */
    public String redeliver(String notificationId) {
        Notification result = notificationCenter.redeliver(notificationId);
        log.info("[Admin] 重投死信 {}，最新状态={}", notificationId, result.status());
        return result.status().name();
    }

    /**
     * 死信队列概览（看板数据）。
     *
     * @param tenantId 租户 ID
     * @param count    死信数量
     * @param items    死信明细
     */
    public record DeadLetterOverview(String tenantId, int count, List<DeadLetterItem> items) {
    }

    /**
     * 单条死信明细。
     *
     * @param id        通知 ID
     * @param channel   渠道
     * @param target    接收方
     * @param attempts  已尝试次数
     * @param lastError 最近错误
     */
    public record DeadLetterItem(String id, String channel, String target,
                                 int attempts, String lastError) {
    }
}