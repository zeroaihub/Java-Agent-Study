package com.zero.ai.agentstudy.day06workflow.workflow.model;

import com.zero.ai.agentstudy.day06workflow.workflow.core.NodeStatus;
import lombok.Getter;
import lombok.ToString;

/**
 * WorkflowExecutionLog —— 单个节点一次执行的日志记录（可观测性核心）。
 *
 * <p>教学要点：Workflow 相比普通 Java 方法调用最大的价值之一就是「可观测」。
 * 每个节点跑了多久、成功还是失败、重试了几次，都记录下来，
 * 出问题时能精确定位到哪一步。这是企业级流程引擎的刚需。</p>
 *
 * @author ZeroAi
 */
@Getter
@ToString
public class WorkflowExecutionLog {

    /** 节点名 */
    private final String nodeName;

    /** 该节点的最终状态 */
    private final NodeStatus status;

    /** 说明信息 */
    private final String message;

    /** 实际发生的重试次数 */
    private final int retryCount;

    /** 耗时（毫秒） */
    private final long costMs;

    public WorkflowExecutionLog(String nodeName, NodeStatus status,
                                String message, int retryCount, long costMs) {
        this.nodeName = nodeName;
        this.status = status;
        this.message = message;
        this.retryCount = retryCount;
        this.costMs = costMs;
    }

    /**
     * 格式化成一行可读日志，用于最终输出给用户看执行轨迹。
     *
     * @return 如 "✅ WeatherNode | SUCCESS | 12ms | retry=0 | 查到天气"
     */
    public String toReadableLine() {
        String icon = (status == NodeStatus.FAILED) ? "❌" : "✅";
        return String.format("%s %s | %s | %dms | retry=%d | %s",
                icon, nodeName, status, costMs, retryCount, message);
    }
}