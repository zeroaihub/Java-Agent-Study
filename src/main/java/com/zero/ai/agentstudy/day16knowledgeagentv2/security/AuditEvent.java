package com.zero.ai.agentstudy.day16knowledgeagentv2.security;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.model.AgentContext;

import java.time.Instant;

/**
 * 审计事件（Chapter 18）。
 *
 * <p>审计 ≠ 可观测性：可观测关注「系统健康与性能」，审计关注「谁在何时对什么做了什么」，
 * 是合规与追责的证据链，要求不可篡改、长期留存。每条问答、每次权限判定都应留痕，
 * 且落库前经过 PII 脱敏。</p>
 *
 * @param traceId 关联链路
 * @param tenant  租户
 * @param userId  操作者
 * @param action  动作（如 QUERY / ACCESS_DENIED / INJECTION_BLOCKED）
 * @param detail  详情（已脱敏）
 * @param at      发生时间
 * @author ZeroAi
 */
public record AuditEvent(
        String traceId,
        String tenant,
        String userId,
        String action,
        String detail,
        Instant at
) {

    public AuditEvent {
        AgentException.requireText(action, "action");
    }

    /** 从上下文构造一条已脱敏的审计事件。 */
    public static AuditEvent of(AgentContext ctx, String action, String rawDetail) {
        return new AuditEvent(
                ctx.traceId(), ctx.tenantId().value(), ctx.userId().value(),
                action, PiiRedactor.redact(rawDetail), Instant.now());
    }
}