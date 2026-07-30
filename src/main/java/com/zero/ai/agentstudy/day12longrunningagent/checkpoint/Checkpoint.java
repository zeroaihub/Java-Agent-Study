package com.zero.ai.agentstudy.day12longrunningagent.checkpoint;

import java.time.Instant;
import java.util.Map;

/**
 * 检查点（Checkpoint / Snapshot）。
 *
 * <p>Checkpoint 是某个时刻 Session 运行进度的一份"快照"，记录了当时的 stepIndex、
 * 重试次数与中间产物。崩溃恢复时，Runtime 读回最近一个 Checkpoint，即可从该点续跑，
 * 而不是从头重来（这正是 Long Running Agent 相对普通任务的关键能力）。</p>
 *
 * <p>本类为不可变对象（所有字段 final），一经创建不可修改，符合"快照"语义。</p>
 */
public final class Checkpoint {

    private final String sessionId;
    private final int stepIndex;
    private final int retryCount;
    private final Map<String, Object> attributes; // 快照时的中间产物（防御性拷贝）
    private final Instant createdAt;

    public Checkpoint(String sessionId, int stepIndex, int retryCount, Map<String, Object> attributes) {
        this.sessionId = sessionId;
        this.stepIndex = stepIndex;
        this.retryCount = retryCount;
       // 防御性拷贝，确保快照与后续对 Context 的修改彻底隔离
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        this.createdAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getStepIndex() {
        return stepIndex;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Checkpoint{sessionId=" + sessionId
                + ", stepIndex=" + stepIndex
                + ", retryCount=" + retryCount
                + ", createdAt=" + createdAt + "}";
    }
}