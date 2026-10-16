package com.zero.ai.agentstudy.day14wechatagent.wechatsession.application;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 会话相关配置项。
 * <p>
 * 通过 {@code wechat.session.*} 前缀外部化，所有字段均带合理默认值，
 * 保证零配置即可运行。生产环境可在 application.yml 覆写。
 *
 * <pre>
 * wechat:
 *   session:
 *     idle-threshold: 5m      # 空闲阈值：超过则标记 IDLE
 *     expire-threshold: 30m   # 过期阈值：超过则标记 EXPIRED 并清理
 * </pre>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "wechat.session")
public class SessionProperties {

    /** 空闲阈值：ACTIVE 会话超过该时长未交互则转 IDLE。默认 5 分钟。 */
    private Duration idleThreshold = Duration.ofMinutes(5);

    /** 过期阈值：会话超过该时长未交互则转 EXPIRED 并清理。默认 30 分钟。 */
    private Duration expireThreshold = Duration.ofMinutes(30);
}