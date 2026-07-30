package com.zero.ai.agentstudy.day12longrunningagent.event;

/**
 * 事件监听器（订阅者）。
 *
 * <p>实现本接口并声明关心的事件类型，即可订阅事件总线。
 * 发布者与订阅者通过 {@link EventBus} 解耦：发布者不知道谁在监听，
 * 订阅者不知道谁在发布——这是事件驱动架构的核心价值。</p>
 *
 * <p>符合开闭原则：新增一种对事件的反应，只需新增一个 Listener，
 * 无需改动任何发布方代码。</p>
 */
public interface EventListener {

    /**
     * 该监听器关心的事件类型（与 {@link AgentEvent#type()} 匹配）。
     *
     * <p>返回 {@code "*"} 表示订阅所有事件（通配，常用于审计/日志）。</p>
     */
    String interestedType();

    /**
     * 事件回调。
     *
     * <p>约定：监听器内部应自行处理异常，不应让异常穿透到总线，
     * 以免影响其它监听器。EventBus 也会做兜底隔离。</p>
     *
     * @param event 发生的事件
     */
    void onEvent(AgentEvent event);

    /** 通配类型常量：订阅所有事件。 */
    String WILDCARD = "*";
}