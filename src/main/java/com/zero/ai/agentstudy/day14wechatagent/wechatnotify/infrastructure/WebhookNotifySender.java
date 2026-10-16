package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.infrastructure;

import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.Notification;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.NotifyChannel;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.SendResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.port.NotifySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 通用 Webhook 通知发送器（Mock 实现）。
 *
 * <p>认领 {@link NotifyChannel#WEBHOOK}，把通知内容以 POST 方式推送到第三方系统。
 * target 为回调 URL。</p>
 *
 * <p><strong>生产替换说明（TODO）：</strong>真实实现应：</p>
 * <ul>
 *     <li>用 {@code RestClient}/{@code WebClient} POST JSON payload 到 target URL。</li>
 *     <li>对 payload 做签名（HMAC-SHA256），接收方验签防伪造。</li>
 *     <li>设置连接/读取超时，非 2xx 响应视为失败触发重试。</li>
 * </ul>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class WebhookNotifySender implements NotifySender {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifySender.class);

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.WEBHOOK;
    }

    @Override
    public SendResult send(Notification notification) {
        String url = notification.target();
        if (!url.startsWith("http")) {
            return SendResult.fail("非法 Webhook URL: " + url);
        }
        // TODO 生产：RestClient POST 签名 payload，处理超时与非 2xx
        String messageId = "webhook-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[Notify:Webhook] -> url={} title='{}' 模拟回调成功 messageId={}",
                url, notification.title(), messageId);
        return SendResult.ok(messageId);
    }
}