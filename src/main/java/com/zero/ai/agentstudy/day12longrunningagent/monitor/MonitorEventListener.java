package com.zero.ai.agentstudy.day12longrunningagent.monitor;

import com.zero.ai.agentstudy.day12longrunningagent.event.AgentEvent;
import com.zero.ai.agentstudy.day12longrunningagent.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 监控事件监听器：把领域事件翻译成运行指标。
 *
 * <p>这是 Chapter 06 事件驱动与本章监控的交汇点，也是"通配监听器"的
 * 典型用途——它订阅 <b>所有</b> 事件（{@code "*"}），根据事件类型分别
 * 累加对应指标，并输出结构化日志（黑匣子）。</p>
 *
 * <p><b>为什么用监听器而非在业务代码里直接埋点？</b> 因为埋点与业务
 * 彻底解耦：业务只管发布事件，"记什么指标、打什么日志"全在这里集中定义。
 * 想新增一个指标维度，改这一个类即可，不必翻遍全工程。</p>
 */
@Component
public class MonitorEventListener implements EventListener {

    private static final Logger log = LoggerFactory.getLogger(MonitorEventListener.class);

    private final AgentMetrics metrics;

    public MonitorEventListener(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    /** 订阅所有事件。 */
    @Override
    public String interestedType() {
        return EventListener.WILDCARD;
    }

    @Override
    public void onEvent(AgentEvent event) {
        // 每来一个事件先累加"事件发布总数"
        metrics.increment(AgentMetrics.EVENT_PUBLISHED);

        // 结构化日志：统一格式，便于日志平台（ELK/Loki）检索与告警
        log.info("[Monitor] event type={}, eventId={}, sessionId={}, at={}",
                event.type(), event.eventId(), event.sessionId(), event.occurredAt());

        // 按事件类型翻译成对应业务指标
        switch (event.type()) {
            case "TASK_SUCCESS" -> metrics.increment(AgentMetrics.TASK_SUCCESS);
            case "TASK_FAILED" -> metrics.increment(AgentMetrics.TASK_FAILED);
            case "TASK_RETRIED" -> metrics.increment(AgentMetrics.TASK_RETRIED);
            case "TASK_DEAD" -> metrics.increment(AgentMetrics.TASK_DEAD);
            case "SESSION_STARTED" -> {
                metrics.increment(AgentMetrics.SESSION_STARTED);
                metrics.addGauge(AgentMetrics.GAUGE_RUNNING_SESSIONS, 1);
            }
            case "SESSION_COMPLETED", "SESSION_FAILED" -> {
                metrics.increment(AgentMetrics.SESSION_COMPLETED);
                metrics.addGauge(AgentMetrics.GAUGE_RUNNING_SESSIONS, -1);
            }
            case "CHECKPOINT_SAVED" -> metrics.increment(AgentMetrics.CHECKPOINT_SAVED);
            case "RECOVERY_TRIGGERED" -> metrics.increment(AgentMetrics.RECOVERY_TRIGGERED);
            default -> {
                // 未知事件类型：只计入总数，不额外处理
            }
        }
    }
}