package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.Notification;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.NotifyChannel;
import com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model.SendResult;

/**
 * 通知发送端口（领域端口，策略模式）。
 *
 * <p>每个渠道（企业微信、邮件、短信、Webhook）对应一个实现。通知中心根据
 * {@code Notification.channel()} 用 {@link #supports(NotifyChannel)} 路由到匹配的发送器，
 * 体现「开闭原则」：新增渠道只需新增实现，无需改动通知中心。</p>
 *
 * <p>本端口<strong>不依赖任何 Spring 注解</strong>，保持领域纯净；实现类在
 * infrastructure 层用 {@code @Component} 声明为 Bean。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public interface NotifySender {

    /**
     * 是否支持指定渠道。
     *
     * @param channel 通知渠道
     * @return true 表示本发送器负责该渠道
     */
    boolean supports(NotifyChannel channel);

    /**
     * 执行投递。
     *
     * <p>约定：以返回值 {@link SendResult} 表达成败，避免抛异常打断通知中心；
     * 网络等异常应在实现内捕获并转为 {@code SendResult.fail(...)}。</p>
     *
     * @param notification 待投递通知
     * @return 投递结果
     */
    SendResult send(Notification notification);
}