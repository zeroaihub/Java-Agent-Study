package com.zero.ai.agentstudy.day12longrunningagent.session;

import com.zero.ai.agentstudy.day12longrunningagent.lifecycle.AgentState;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent 会话（Session）—— 一个长任务的"实例"与"身份"。
 *
 * <p>Session 是长任务的最小可持久化单元。它聚合了：身份（sessionId）、类型（agentType）、
 * 当前状态（state）、运行上下文（context）、心跳/租约信息（heartbeat/owner），以及时间戳。</p>
 *
 * <p>崩溃恢复时，Recovery 扫描所有非终态的 Session，根据其 context.stepIndex 续跑。</p>
 */
public class AgentSession {

    /** 全局唯一标识，贯穿日志作为 TraceId。 */
    private final String sessionId;

    /** Agent 类型（决定用哪套 Step 定义执行），例如 "github-trending"。 */
    private final String agentType;

    /** 当前生命周期状态。变更必须经 AgentStateMachine 校验。 */
    private volatile AgentState state;

    /** 运行上下文（进度指针 + 中间产物）。 */
    private final AgentContext context;

    /** 最近一次失败原因（进入 FAILED / RETRYING 时记录）。 */
    private volatile String lastError;

    /** 心跳时间：多节点场景用于判断租约是否过期。 */
    private volatile Instant lastHeartbeat;

    /** 当前持有该 Session 的节点标识（租约拥有者）。 */
    private volatile String owner;

    private final Instant createdAt;
    private volatile Instant updatedAt;

    public AgentSession(String agentType) {
        this(UUID.randomUUID().toString(), agentType);
    }

    public AgentSession(String sessionId, String agentType) {
        this.sessionId = sessionId;
        this.agentType = agentType;
        this.state = AgentState.CREATED;
        this.context = new AgentContext();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.lastHeartbeat = this.createdAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentType() {
        return agentType;
    }

    public AgentState getState() {
        return state;
    }

    /**
     * 内部状态写入。注意：外部禁止直接调用，必须经由 Runtime 结合
     * AgentStateMachine.transit(...) 校验后调用，以杜绝非法流转。
     */
    public void setState(AgentState state) {
        this.state = state;
        touch();
    }

    public AgentContext getContext() {
        return context;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
        touch();
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void heartbeat() {
        this.lastHeartbeat = Instant.now();
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    @Override
    public String toString() {
        return "AgentSession{id=" + sessionId
                + ", type=" + agentType
                + ", state=" + state
                + ", " + context + "}";
    }
}