package com.zero.ai.agentstudy.day14wechatagent.wechatapi.web;

import com.zero.ai.agentstudy.day14wechatagent.wechatapi.dto.ApiResponse;
import com.zero.ai.agentstudy.day14wechatagent.wechatapi.dto.SendNotificationRequest;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.application.NotificationCenter;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.Notification;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.NotifyChannel;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知中心 REST 接口（入站适配器 / 六边形架构的 Driving Adapter）。
 *
 * <p>本 Controller 是「协议层」的门面：把 HTTP 请求翻译成对应用服务
 * {@link NotificationCenter} 的调用，再把领域返回翻译成统一 {@link ApiResponse}。
 * <strong>Controller 不写任何业务逻辑</strong>——它只做参数校验、翻译、编排，
 * 业务规则全在应用层与领域层。这是保持领域纯净、可测试的关键。</p>
 *
 * <p>接口清单：</p>
 * <ul>
 *     <li>POST /api/notifications：发送企业微信 Markdown 通知。</li>
 *     <li>GET  /api/notifications/dead-letters/{tenantId}：查询某租户死信。</li>
 *     <li>POST /api/notifications/{id}/redeliver：人工重投死信。</li>
 * </ul>
 *
 * @author ZeroHub AI Agent Platform
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationCenter notificationCenter;

    /**
     * 构造函数注入应用服务。
     *
     * @param notificationCenter 通知中心
     */
    public NotificationController(NotificationCenter notificationCenter) {
        this.notificationCenter = notificationCenter;
    }

    /**
     * 发送企业微信 Markdown 通知。
     *
     * @param request 发送请求（已通过 Bean Validation 校验）
     * @return 投递后的通知视图
     */
    @PostMapping
    public ApiResponse<NotificationView> send(@Valid @RequestBody SendNotificationRequest request) {
        log.info("[API] 收到发送通知请求，tenant={}, group={}", request.tenantId(), request.groupId());

        // DTO -> 领域对象：带 dedupeKey 则走去重工厂，否则走普通工厂
        Notification notification = (request.dedupeKey() != null && !request.dedupeKey().isBlank())
                ? Notification.withDedupe(request.tenantId(), NotifyChannel.WECHAT_WORK,
                        request.groupId(), request.title(), request.markdown(), request.dedupeKey())
                : Notification.wechatMarkdown(request.tenantId(), request.groupId(),
                        request.title(), request.markdown());

        Notification sent = notificationCenter.send(notification);
        return ApiResponse.ok(NotificationView.from(sent));
    }

    /**
     * 查询某租户的死信队列。
     *
     * @param tenantId 租户 ID
     * @return 死信通知视图列表
     */
    @GetMapping("/dead-letters/{tenantId}")
    public ApiResponse<List<NotificationView>> deadLetters(@PathVariable String tenantId) {
        List<NotificationView> views = notificationCenter.deadLetters(tenantId).stream()
                .map(NotificationView::from)
                .toList();
        return ApiResponse.ok(views);
    }

    /**
     * 人工重投一条死信。
     *
     * @param id 死信通知 ID
     * @return 重投后的通知视图
     */
    @PostMapping("/{id}/redeliver")
    public ApiResponse<NotificationView> redeliver(@PathVariable String id) {
        log.info("[API] 收到死信重投请求，id={}", id);
        Notification result = notificationCenter.redeliver(id);
        return ApiResponse.ok(NotificationView.from(result));
    }

    /**
     * 通知出站视图（响应 DTO）：只暴露调用方需要的字段，隐藏领域内部结构。
     *
     * @param id      通知 ID
     * @param channel 渠道
     * @param target  接收方
     * @param status  投递状态
     * @param attempts 已尝试次数
     * @param lastError 最近错误
     */
    public record NotificationView(String id, String channel, String target,
                                   String status, int attempts, String lastError) {

        /**
         * 从领域对象构造视图。
         *
         * @param n 通知领域对象
         * @return 视图
         */
        static NotificationView from(Notification n) {
            return new NotificationView(n.id(), n.channel().name(), n.target(),
                    n.status().name(), n.attempts(), n.lastError());
        }
    }
}