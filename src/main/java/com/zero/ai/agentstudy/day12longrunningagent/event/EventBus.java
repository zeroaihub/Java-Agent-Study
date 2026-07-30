package com.zero.ai.agentstudy.day12longrunningagent.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线（内存实现）。
 *
 * <p>发布/订阅（Pub-Sub）模式的中枢：发布者调用 {@link #publish(AgentEvent)}，
 * 总线按事件类型路由给所有匹配的 {@link EventListener}。发布者与订阅者
 * 无需相互引用，实现时空解耦。</p>
 *
 * <p>本内存版为 <b>同步派发</b>：publish 会在调用线程上依次通知所有监听器。
 * 优点是简单、有序、便于测试；生产环境若需异步/削峰，可把派发提交到
 * 线程池，或干脆换成 Kafka/RabbitMQ 这类真正的消息中间件——上层
 * 发布/订阅代码不变。</p>
 *
 * <p>健壮性：单个监听器抛异常不会中断其它监听器（异常隔离），
 * 也不会让发布者失败。</p>
 */
@Component
public class EventBus implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    /** type → 监听器列表；通配监听器单独存放在 WILDCARD 桶。 */
    private final Map<String, List<EventListener>> registry = new ConcurrentHashMap<>();

    /** 延迟获取所有 EventListener Bean，避免构造期循环依赖。 */
    private final ObjectProvider<EventListener> listenerProvider;

    public EventBus(ObjectProvider<EventListener> listenerProvider) {
        this.listenerProvider = listenerProvider;
    }

    /**
     * 所有单例 Bean 实例化完成后，自动装配所有 EventListener 并完成订阅。
     *
     * <p>使用 {@link SmartInitializingSingleton} 而非构造器注入 List，是因为
     * 部分 EventListener（如 TrendingScheduler）依赖 AgentRuntime → EventBus，
     * 若在构造期解析会形成循环依赖。推迟到全部 Bean 就绪后再收集，天然打破环。</p>
     */
    @Override
    public void afterSingletonsInstantiated() {
        listenerProvider.orderedStream().forEach(this::subscribe);
        log.info("[EventBus] 已装配监听器，订阅类型={}", registry.keySet());
    }

    /** 动态订阅一个监听器（运行期也可调用）。 */
    public void subscribe(EventListener listener) {
        String type = listener.interestedType();
        registry.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** 取消订阅。 */
    public void unsubscribe(EventListener listener) {
        List<EventListener> list = registry.get(listener.interestedType());
        if (list != null) {
            list.remove(listener);
        }
    }

    /**
     * 发布事件：同步派发给所有匹配的监听器。
     *
     * <p>匹配 = 精确类型订阅者 + 通配（"*"）订阅者。</p>
     */
    public void publish(AgentEvent event) {
        if (event == null) {
            return;
        }
        log.debug("[EventBus] 发布事件 type={}, eventId={}, sessionId={}",
                event.type(), event.eventId(), event.sessionId());

        // 1) 精确类型订阅者
        dispatch(registry.get(event.type()), event);
        // 2) 通配订阅者（如审计、全量日志）
        dispatch(registry.get(EventListener.WILDCARD), event);
    }

    /** 向一组监听器派发，逐个 try-catch 做异常隔离。 */
    private void dispatch(List<EventListener> listeners, AgentEvent event) {
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (EventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception ex) {
                // 单个监听器失败不影响其它监听器与发布者
                log.error("[EventBus] 监听器处理事件异常 listener={}, eventId={}",
                        listener.getClass().getSimpleName(), event.eventId(), ex);
            }
        }
    }

    /** 某类型当前的订阅者数量（监控/自检用）。 */
    public int listenerCount(String type) {
        List<EventListener> list = registry.get(type);
        return list == null ? 0 : list.size();
    }
}