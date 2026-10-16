package com.zero.ai.agentstudy.day15knowledgeplatform.core.event;

/**
 * 领域事件发布端口（出站端口）。
 *
 * <p>领域层只声明「我要发布事件」，至于事件是被 Spring 的 {@code ApplicationEventPublisher} 消费、
 * 还是被 Kafka / RabbitMQ 投递、还是被内存收集器记录下来用于测试，领域层一概不关心。</p>
 *
 * <p>这就是六边形架构里的依赖倒置（DIP）：高层策略（领域）不依赖低层机制（消息中间件），
 * 二者都依赖抽象。</p>
 *
 * <p>为什么放在 {@code core}？因为「发布事件」是所有模块都需要的能力，
 * 且它只依赖 {@link DomainEvent} 这一个本包内的抽象，符合 core 零框架依赖的约定。</p>
 *
 * @author ZeroAi
 */
public interface DomainEventPublisher {

    /**
     * 发布一个领域事件。
     *
     * @param event 待发布的事件，不可为空
     */
    void publish(DomainEvent event);
}
