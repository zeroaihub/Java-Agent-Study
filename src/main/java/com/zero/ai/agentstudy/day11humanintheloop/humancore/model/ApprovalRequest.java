package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 审批请求（聚合根 / Aggregate Root）。
 *
 * <p>与 {@link AgentAction} / {@link ApprovalDecision} 这些不可变值对象不同，
 * {@code ApprovalRequest} 是有生命周期、会随审批推进而变化的"实体"。它把一次审批
 * 需要的所有信息聚合在一起：谁发起的、审批哪个动作、当前到了哪个状态、走到了第几级、
 * 历史上都有谁做过什么决策。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>状态字段 {@link #status} 只允许由 {@code ApprovalStateMachine} 驱动变更，
 *       外部不能直接 set，避免非法流转。</li>
 *   <li>决策历史 {@link #decisions} 只增不改，形成天然的审计链。</li>
 *   <li>{@link #requiredLevels} 支持多级会签：1 表示单级，N 表示需要 N 个层级依次通过。</li>
 * </ul>
 */
public class ApprovalRequest {

    /** 审批请求唯一 ID。 */
    private final String requestId;

    /** 被审批的 Agent 动作（不可变）。 */
    private final AgentAction action;

    /** 风险等级（由 RiskPolicy 评估得出）。 */
    private final RiskLevel riskLevel;

    /** 需要的审批级数（多级会签）；1 = 单级。 */
    private final int requiredLevels;

    /** 发起时间。 */
    private final Instant createdAt;

    /** 超时时间点（到点未决策则可判定 TIMEOUT）。 */
    private final Instant expireAt;

    /** 当前状态（受状态机保护）。 */
    private ApprovalStatus status;

    /** 当前已通过的级数（0 表示还没人批）。 */
    private int approvedLevels;

    /** 决策历史（只增不改，审计链）。 */
    private final List<ApprovalDecision> decisions = new ArrayList<>();

    /**
     * 完整构造器。一般由 {@code ApprovalEngine} 在创建审批时调用。
     */
    public ApprovalRequest(String requestId,
                           AgentAction action,
                           RiskLevel riskLevel,
                           int requiredLevels,
                           Instant createdAt,
                           Instant expireAt) {
        this.requestId = Objects.requireNonNull(requestId, "requestId 不能为空");
        this.action = Objects.requireNonNull(action, "action 不能为空");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        if (requiredLevels < 1) {
            throw new IllegalArgumentException("requiredLevels 至少为 1，实际=" + requiredLevels);
        }
        this.requiredLevels = requiredLevels;
        this.createdAt = (createdAt == null) ? Instant.now() : createdAt;
        this.expireAt = expireAt;
        this.status = ApprovalStatus.PENDING;
        this.approvedLevels = 0;
    }

    /**
     * 便捷工厂：单级审批，默认 24 小时超时。
     */
    public static ApprovalRequest single(AgentAction action, RiskLevel riskLevel) {
        Instant now = Instant.now();
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                action,
                riskLevel,
                1,
                now,
                now.plusSeconds(24 * 3600)
        );
    }

    /**
     * 便捷工厂：多级会签。
     */
    public static ApprovalRequest multiLevel(AgentAction action, RiskLevel riskLevel, int levels) {
        Instant now = Instant.now();
        return new ApprovalRequest(
                UUID.randomUUID().toString(),
                action,
                riskLevel,
                levels,
                now,
                now.plusSeconds(24 * 3600)
        );
    }

    // ---------------- 供状态机调用的受控变更方法 ----------------

    /**
     * 由状态机调用：应用一个新状态。
     * <p>注意：这里不做合法性校验，合法性由 {@code ApprovalStateMachine} 保证。</p>
     */
    public void applyStatus(ApprovalStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus);
    }

    /**
     * 记录一次决策（审计链），并在需要时推进已通过级数。
     */
    public void recordDecision(ApprovalDecision decision) {
        this.decisions.add(Objects.requireNonNull(decision));
        if (decision.isApproval()) {
            this.approvedLevels++;
        }
    }

    /**
     * 是否所有层级都已通过（多级会签完成条件）。
     */
    public boolean allLevelsApproved() {
        return approvedLevels >= requiredLevels;
    }

    /**
     * 是否已超时（当前时间超过 expireAt）。
     */
    public boolean isExpired() {
        return expireAt != null && Instant.now().isAfter(expireAt);
    }

    // ---------------- getters ----------------

    public String getRequestId() {
        return requestId;
    }

    public AgentAction getAction() {
        return action;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public int getRequiredLevels() {
        return requiredLevels;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public int getApprovedLevels() {
        return approvedLevels;
    }

    public List<ApprovalDecision> getDecisions() {
        return Collections.unmodifiableList(decisions);
    }
}