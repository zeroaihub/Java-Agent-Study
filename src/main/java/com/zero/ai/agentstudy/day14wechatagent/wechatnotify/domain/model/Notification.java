package com.zero.ai.agentstudy.day14wechatagent.wechatnotify.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 通知聚合根（Notification）。
 *
 * <p>一条 {@code Notification} 描述「要把什么内容、通过哪个渠道、送达给谁」，
 * 并承载完整的投递生命周期状态：</p>
 * <ul>
 *     <li>{@code id} —— 通知唯一标识。</li>
 *     <li>{@code tenantId} —— 所属租户（多租户隔离）。</li>
 *     <li>{@code channel} —— 投递渠道，见 {@link NotifyChannel}。</li>
 *     <li>{@code target} —— 接收方标识（企业微信 userId、群 chatId、邮箱等）。</li>
 *     <li>{@code title} / {@code content} ——标题与正文（支持 Markdown）。</li>
 *     <li>{@code status} / {@code attempts} —— 投递状态与已尝试次数。</li>
 * </ul>
 *
 * <p>它是<strong>可变实体</strong>：投递结果由 application 层的通知中心驱动更新，
 * 状态流转见 {@link NotifyStatus}。幂等键 {@code dedupeKey} 用于避免重复投递（见教程避坑）。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public class Notification {

    private final String id;
    private final String tenantId;
    private final NotifyChannel channel;
    private final String target;
    private final String title;
    private final String content;
    private final boolean markdown;
    private final String dedupeKey;
    private final int maxRetries;
    private final Instant createdAt;

    private NotifyStatus status;
    private int attempts;
    private String lastError;
    private Instant sentAt;

    /**
     * 完整构造器。
     *
     * @param tenantId  租户 ID
     * @param channel   投递渠道
     * @param target    接收方标识
     * @param title     标题
     * @param content   正文
     * @param markdown  正文是否为 Markdown
     * @param dedupeKey 幂等键（可空；非空时用于去重）
     * @param maxRetries 最大重试次数（>=0）
     */
    public Notification(String tenantId, NotifyChannel channel, String target,
                        String title, String content, boolean markdown,
                        String dedupeKey, int maxRetries) {
        this.id = UUID.randomUUID().toString();
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId 不能为空");
        this.channel = Objects.requireNonNull(channel, "channel 不能为空");
        this.target = Objects.requireNonNull(target, "target 不能为空");
        this.title = title == null ? "" : title;
        this.content = Objects.requireNonNull(content, "content 不能为空");
        this.markdown = markdown;
        this.dedupeKey = dedupeKey;
        this.maxRetries = Math.max(0, maxRetries);
        this.createdAt = Instant.now();
        this.status = NotifyStatus.PENDING;
        this.attempts = 0;
    }

    /**
     * 便捷工厂：企业微信 Markdown 通知（重试 2 次）。
     *
     * @param tenantId 租户 ID
     * @param target   接收方（userId 或 chatId）
     * @param title    标题
     * @param content  Markdown 正文
     * @return 通知实例
     */
    public static Notification wechatMarkdown(String tenantId, String target, String title, String content) {
        return new Notification(tenantId, NotifyChannel.WECHAT_WORK, target, title, content, true, null, 2);
    }

    /**
     * 便捷工厂：带幂等键的通知（用于对外副作用去重）。
     *
     * @param tenantId  租户 ID
     * @param channel   渠道
     * @param target    接收方
     * @param title     标题
     * @param content   正文
     * @param dedupeKey 幂等键
     * @return 通知实例
     */
    public static Notification withDedupe(String tenantId, NotifyChannel channel, String target,
                                          String title, String content, String dedupeKey) {
        return new Notification(tenantId, channel, target, title, content, true, dedupeKey, 2);
    }

    /** 标记进入投递中并累加尝试次数。 */
    public void markSending() {
        this.attempts++;
    }

    /** 标记投递成功。 */
    public void markSent() {
        this.status = NotifyStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    /**
     * 标记本次投递失败：可重投则回到 PENDING，否则进入 DEAD 死信。
     *
     * @param error 错误信息
     */
    public void markFailed(String error) {
        this.lastError = error;
        if (canRetry()) {
            this.status = NotifyStatus.PENDING;
        } else {
            this.status = NotifyStatus.DEAD;
        }
    }

    /** 主动打入死信（不可重试的错误，如目标非法）。 */
    public void markDead(String error) {
        this.status = NotifyStatus.DEAD;
        this.lastError = error;
    }

    /**
     * 是否还可重试（尝试次数未超过 maxRetries）。
     *
     * @return true 表示可再投一次
     */
    public boolean canRetry() {
        return attempts <= maxRetries;
    }

    /** @return 通知唯一标识 */
    public String id() {
        return id;
    }

    /** @return 租户 ID */
    public String tenantId() {
        return tenantId;
    }

    /** @return 投递渠道 */
    public NotifyChannel channel() {
        return channel;
    }

    /** @return 接收方标识 */
    public String target() {
        return target;
    }

    /** @return 标题 */
   public String title() {
        return title;
    }

    /** @return 正文 */
    public String content() {
        return content;
    }

    /** @return 正文是否为 Markdown */
    public boolean markdown() {
        return markdown;
    }

    /** @return 幂等键（可能为 null） */
    public String dedupeKey() {
        return dedupeKey;
    }

    /** @return 最大重试次数 */
    public int maxRetries() {
        return maxRetries;
    }

    /** @return 当前状态 */
    public NotifyStatus status() {
        return status;
    }

    /** @return 已尝试次数 */
    public int attempts() {
        return attempts;
    }

    /** @return 最近一次错误（可能为 null） */
    public String lastError() {
        return lastError;
    }

    /** @return 创建时间 */
    public Instant createdAt() {
        return createdAt;
    }

    /** @return 送达时间（可能为 null） */
    public Instant sentAt() {
        return sentAt;
    }

    @Override
    public String toString() {
        return "Notification{id='" + id + "', channel=" + channel + ", target='" + target
                + "', status=" + status + ", attempts=" + attempts + "}";
    }
}