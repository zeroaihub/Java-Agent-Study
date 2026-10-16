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
 * 企业微信通知发送器（主渠道，Mock 实现）。
 *
 * <p>认领 {@link NotifyChannel#WECHAT_WORK}，模拟把 Markdown 消息推送给企业微信用户或群。</p>
 *
 * <p><strong>生产替换说明（TODO）：</strong>真实实现应调用企业微信服务端 API：</p>
 * <ul>
 *     <li>应用消息：{@code POST /cgi-bin/message/send}（需 access_token，body 用 markdown 类型）。</li>
 *     <li>群机器人：{@code POST /cgi-bin/webhook/send?key=xxx}（markdown 类型，注意 4096 字节上限）。</li>
 *     <li>需处理 access_token 缓存与刷新、频率限制（每分钟 20 条/机器人）、错误码重试。</li>
 * </ul>
 *
 * @author ZeroHub AI Agent Platform
 */
@Component
public class WechatWorkNotifySender implements NotifySender {

    private static final Logger log = LoggerFactory.getLogger(WechatWorkNotifySender.class);

    /** 企业微信群机器人 markdown 内容上限（字节）。 */
    private static final int WECHAT_MARKDOWN_LIMIT = 4096;

    @Override
    public boolean supports(NotifyChannel channel) {
        return channel == NotifyChannel.WECHAT_WORK;
    }

    @Override
    public SendResult send(Notification notification) {
        // 校验：内容长度超限属不可重试错误（重试也没用）
        int bytes = notification.content().getBytes().length;
        if (bytes > WECHAT_MARKDOWN_LIMIT) {
            return SendResult.fail("Markdown 内容超过企业微信上限 " + WECHAT_MARKDOWN_LIMIT + " 字节: " + bytes);
        }

        // TODO 生产：此处应调用企业微信 API，处理 access_token 与错误码
        String messageId = "wxwork-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[Notify:WechatWork] -> target={} title='{}' markdown={} 字节={} 模拟推送成功 messageId={}",
                notification.target(), notification.title(), notification.markdown(), bytes, messageId);

        return SendResult.ok(messageId);
    }
}