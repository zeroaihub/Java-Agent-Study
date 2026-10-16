package com.zero.ai.agentstudy.day15knowledgeplatform.infrastructure;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.event.DomainEvent;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.event.DomainEventPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存领域事件发布器（出站适配器 / 测试替身）。
 *
 * <p>它把事件收集到一个线程安全的列表里，而不是真的投递到消息中间件。这样带来两个好处：</p>
 * <ol>
 *     <li><b>零依赖可跑通</b>：不需要 Kafka / RabbitMQ，{@code main()} 里直接跑完整流水线。</li>
 *     <li><b>可断言</b>：测试与 Demo 里可以直接断言「上传文档后确实发出了 DocumentUploadedEvent」。</li>
 * </ol>
 *
 * <p>生产环境替换为 Kafka / RocketMQ 适配器时，只需实现同一个 {@link DomainEventPublisher} 接口，
 * 领域层与应用层代码一行都不用改。</p>
 *
 * @author ZeroAi
 */
public class InMemoryDomainEventPublisher implements DomainEventPublisher {

    private final List<DomainEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void publish(DomainEvent event) {
        if (event == null) {
            return;
        }
        events.add(event);
        System.out.println("  [event] " + event.getClass().getSimpleName()
                + " / tenant=" + event.tenantId()
                + " / at=" + event.occurredAt());
    }

    /**
     * 返回已发布事件的只读快照。
     */
    public List<DomainEvent> publishedEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * 按事件类型筛选。
     */
    public <E extends DomainEvent> List<E> publishedEventsOfType(Class<E> type) {
        List<E> matched = new ArrayList<>();
        for (DomainEvent e : events) {
            if (type.isInstance(e)) {
                matched.add(type.cast(e));
            }
        }
        return matched;
    }

    /**
     * 清空已收集的事件（Demo / 测试复用实例时使用）。
     */
    public void clear() {
        events.clear();
    }

    public int count() {
        return events.size();
    }
}
