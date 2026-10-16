package com.zero.ai.agentstudy.day15knowledgeplatform.core.event;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.model.TenantId;

import java.time.Instant;

/**
 * 领域事件基础接口（事件驱动架构基石）。
 *
 * <p>知识平台大量使用事件驱动来解耦：文档上传成功后发 {@code DocumentIngestedEvent}，
 * 由解析/切分/向量化模块异步消费；文档删除后发 {@code DocumentDeletedEvent}，
 * 触发 Chunk 与向量的级联删除（删除同步）。</p>
 *
 * <p>所有领域事件都必须携带租户，保证跨模块传递时租户上下文不丢失。</p>
 *
 * @author ZeroAi
 */
public interface DomainEvent {

    /**
     * 事件唯一 ID（用于幂等）。
     */
    String eventId();

    /**
     * 事件所属租户。
     */
    TenantId tenantId();

    /**
     * 事件发生时间。
     */
    Instant occurredAt();

    /**
     * 事件类型名（用于路由/日志）。
     */
    default String eventType() {
        return getClass().getSimpleName();
    }
}