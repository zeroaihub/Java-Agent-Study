package com.zero.ai.agentstudy.day12longrunningagent.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * 队列中的一个可执行任务单元。
 *
 * <p>长生命周期 Agent 的执行不是"一条直线跑完"，而是被拆分为大量小任务，
 * 由任务队列（Task Queue）驱动、调度器（Scheduler）触发。每个任务携带：
 * <ul>
 *   <li>身份：{@code taskId}（去重、追踪）</li>
 *   <li>归属：{@code sessionId}（属于哪个长任务会话）</li>
 *   <li>类型：{@code type}（分发给哪个 handler）</li>
 *   <li>可见时间：{@code visibleAt}（延迟任务，定时任务的核心字段）</li>
 *   <li>尝试次数：{@code attempts}（配合 RetryPolicy 判断是否进死信）</li>
 * </ul>
 */
public class AgentTask {

    private final String taskId;
    private final String sessionId;
    private final String type;

    /** 任务负载（业务参数），简单场景用字符串承载 JSON。 */
    private final String payload;

    /** 可见时间：只有到达该时刻，任务才可被消费（实现延迟/定时）。 */
    private volatile Instant visibleAt;

    /** 已尝试执行次数（首次为 0，每次重试 +1）。 */
    private volatile int attempts;

    private final Instant createdAt;

    public AgentTask(String sessionId, String type, String payload, Instant visibleAt) {
        this.taskId = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.type = type;
        this.payload = payload;
        this.visibleAt = visibleAt == null ? Instant.now() : visibleAt;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    /** 立即可见的任务。 */
    public static AgentTask immediate(String sessionId, String type, String payload) {
        return new AgentTask(sessionId, type, payload, Instant.now());
    }

    /** 延迟 delayMillis 毫秒后可见的任务。 */
    public static AgentTask delayed(String sessionId, String type, String payload, long delayMillis) {
        return new AgentTask(sessionId, type, payload, Instant.now().plusMillis(delayMillis));
    }

    public String getTaskId() {
        return taskId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getVisibleAt() {
        return visibleAt;
    }

    public void setVisibleAt(Instant visibleAt) {
        this.visibleAt = visibleAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 当前时刻任务是否可见（可被消费）。 */
    public boolean isVisible(Instant now) {
        return !visibleAt.isAfter(now);
    }

    @Override
    public String toString() {
        return "AgentTask{taskId=" + taskId + ", sessionId=" + sessionId
                + ", type=" + type + ", attempts=" + attempts
                + ", visibleAt=" + visibleAt + "}";
    }
}