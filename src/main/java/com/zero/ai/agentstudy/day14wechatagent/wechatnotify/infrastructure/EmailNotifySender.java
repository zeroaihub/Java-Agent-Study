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
 * 邮件通知发送器（Mock 实现）。
 *
 * <p>认领 {@link NotifyChannel#EMAIL}，模拟发送邮件。target 为收件邮箱地址。</p>
 *
 * <p><strong>生产替换说明（TODO）：</strong>真实实现可用 Spring 的 {@code JavaMailSender}
 * 或第三方邮件服务（SES/SendGrid）。Markdown 正文需转 HTML 后作为邮件正文。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class EmailNotifySender implements NotifySender {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifySender.class);

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.EMAIL;
    }

    @Override
    public SendResult send(Notification notification) {
        String target = notification.target();
        if (!target.contains("@")) {
            // 非法邮箱：不可重试错误
            return SendResult.fail("非法邮箱地址: " + target);
        }
        // TODO 生产：JavaMailSender 发送，Markdown 转 HTML
        String messageId = "email-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[Notify:Email] -> to={} subject='{}' 模拟发送成功 messageId={}",
                target, notification.title(), messageId);
        return SendResult.ok(messageId);
    }
}