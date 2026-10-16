package com.zero.ai.agentstudy.day14wechatagent.wechatsession.domain.model;

import com.zero.ai.agentstudy.day14wechatagent.wechatcore.domain.model.ChannelType;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 会话（聚合根）。
 * <p>
 * 表示某租户下、某渠道内、某个会话标识（单聊用户或群）对应的一段多轮对话上下文。
 * 与消息值对象不同，会话是**有状态、可变的聚合根**——它随每一轮交互演进，
 * 但所有状态变更都必须通过聚合根暴露的领域方法完成（不允许外部直接改字段），
 * 以保证不变式（invariant）始终成立。
 * <p>
 * 关键不变式：
 * <ul>
 *     <li>会话身份由 {@code (tenantId, channelType, conversationId)} 唯一确定。</li>
 *     <li>{@code lastActiveAt} 只增不减（时间单调）。</li>
 *     <li>状态流转必须经由领域方法，禁止外部 set。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Getter
@ToString
@Builder
public class ConversationSession {

    /** 租户标识 */
    private final String tenantId;

    /** 渠道类型 */
    private final ChannelType channelType;

    /** 会话标识（单聊为用户 ID，群聊为群 ID） */
    private final String conversationId;

    /** 会话创建时间 */
    private final Instant createdAt;

    /** 最近活跃时间（每次交互刷新） */
    private Instant lastActiveAt;

    /** 当前状态 */
    private SessionStatus status;

    /** 多轮交互计数（用于统计与限流） */
    private long turnCount;

    /**
     * 会话级临时上下文槽位（slot）。
     * <p>
     * 用于承载「等待用户补充参数」等短期状态，例如命令系统在
     * WAITING_INPUT 期间暂存已收集的参数。区别于长期 Memory（第六章）。
     */
    @Builder.Default
    private final Map<String, Object> slots = new HashMap<>();

    /**
     * 工厂方法：新建一个活跃会话。
     */
    public static ConversationSession create(String tenantId, ChannelType channelType,
                                             String conversationId) {
        Instant now = Instant.now();
        return ConversationSession.builder()
                .tenantId(tenantId)
                .channelType(channelType)
                .conversationId(conversationId)
                .createdAt(now)
                .lastActiveAt(now)
                .status(SessionStatus.ACTIVE)
                .turnCount(0)
                .build();
    }

    /** 会话身份键：{tenantId}:{channelType}:{conversationId}。 */
    public String sessionKey() {
        return sessionKey(tenantId, channelType, conversationId);
    }

    /**
     * 会话身份键生成（静态版）：{tenantId}:{channelCode}:{conversationId}。
     * <p>
     * 作为身份键的<b>单一事实来源</b>，供仓储在「尚未持有会话对象、
     * 仅凭三元组查找」时复用，避免键格式在多处各写一遍导致漂移。
     */
    public static String sessionKey(String tenantId, ChannelType channelType,
                                    String conversationId) {
        return tenantId + ":" + channelType.getCode() + ":" + conversationId;
    }

    /**
     * 记录一次交互：递增轮次、刷新活跃时间、若空闲则恢复活跃。
     */
    public void touch() {
        this.turnCount++;
        this.lastActiveAt = Instant.now();
        if (this.status == SessionStatus.IDLE) {
            this.status = SessionStatus.ACTIVE;
        }
    }

    /** 切换为等待用户补充输入。 */
    public void waitInput() {
        ensureAlive();
        this.status = SessionStatus.WAITING_INPUT;
    }

    /** 切换为等待人工审批。 */
    public void waitApproval() {
        ensureAlive();
        this.status = SessionStatus.WAITING_APPROVAL;
    }

    /** 恢复活跃（如审批通过 / 用户补齐参数后）。 */
    public void resume() {
        ensureAlive();
        this.status = SessionStatus.ACTIVE;
    }

    /**
     * 基于过期阈值判定并推进状态：
     * 超过 idleThreshold 未活跃 -> IDLE；超过 expireThreshold -> EXPIRED。
     *
     * @param idleThreshold   空闲阈值
     * @param expireThreshold 过期阈值（应大于 idleThreshold）
     * @return 是否发生了状态变化
     */
    public boolean evaluateExpiry(Duration idleThreshold, Duration expireThreshold) {
        Duration inactivity = Duration.between(lastActiveAt, Instant.now());
        SessionStatus before = this.status;
        if (inactivity.compareTo(expireThreshold) >= 0) {
            this.status = SessionStatus.EXPIRED;
        } else if (inactivity.compareTo(idleThreshold) >= 0
                && this.status == SessionStatus.ACTIVE) {
            this.status = SessionStatus.IDLE;
        }
        return before != this.status;
    }

    /** 读取槽位值。 */
    public Object slot(String key) {
        return slots.get(key);
    }

    /** 写入槽位值。 */
    public void putSlot(String key, Object value) {
        slots.put(key, value);
    }

    /** 清空所有槽位（命令完成后调用）。 */
    public void clearSlots() {
        slots.clear();
    }

    private void ensureAlive() {
        if (this.status == SessionStatus.EXPIRED) {
            throw new IllegalStateException("会话已过期，无法变更状态: " + sessionKey());
        }
    }
}