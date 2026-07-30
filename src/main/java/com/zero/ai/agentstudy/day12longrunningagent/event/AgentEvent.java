package com.zero.ai.agentstudy.day12longrunningagent.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent 领域事件。
 *
 * <p>事件驱动（Event Driven）是长生命周期 Agent 从"我主动轮询"进化到
 * "有变化才响应"的关键。一个事件代表"系统里已经发生的某件事实"——
 * 它是过去式的、不可变的、可被多方订阅的。</p>
 *
 * <p>典型事件：会话状态变更、Step 完成、任务失败、外部审批到达、
 * 定时触发到点……发布者只管发布，订阅者各取所需，二者彻底解耦。</p>
 *
 * <p>用不可变 record 承载，保证事件一旦发布就不会被篡改（事件溯源的基石）。</p>
 *
 * @param eventId   事件唯一标识（去重、幂等、追踪）
 * @param type      事件类型（订阅路由键）
 * @param sessionId 关联的会话（可为空，如全局事件）
 * @param payload   事件负载（携带业务数据）
 * @param occurredAt 事件发生时间
 */
public record AgentEvent(
        String eventId,
        String type,
        String sessionId,
        Object payload,
        Instant occurredAt
) {

    public AgentEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("事件 type 不能为空");
        }
        if (eventId == null || eventId.isBlank()) {
            eventId = UUID.randomUUID().toString();
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    /** 便捷工厂：自动生成 eventId 与当前时间。 */
    public static AgentEvent of(String type, String sessionId, Object payload) {
        return new AgentEvent(UUID.randomUUID().toString(), type, sessionId, payload, Instant.now());
    }

    /** 便捷工厂：无会话关联的全局事件。 */
    public static AgentEvent global(String type, Object payload) {
        return of(type, null, payload);
    }
}