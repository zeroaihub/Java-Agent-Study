package com.zero.ai.agentstudy.day06workflow.workflow.model;

import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowState;
import lombok.Getter;

import java.util.List;

/**
 * WorkflowResult —— 一次完整流程运行结束后返回给调用方的总结果。
 *
 * <p>教学要点：把「最终产出 + 宏观状态 + 每一步执行日志」打包返回，
 * 既让业务拿到结果，又让运维/开发看到完整执行轨迹，体现可观测性。</p>
 *
 * @author ZeroAi
 */
@Getter
public class WorkflowResult {

    /** 流程最终状态 */
    private final WorkflowState state;

    /** 本次运行 ID */
    private final String runId;

    /** 最终业务输出（如旅行方案 Markdown） */
    private final String output;

    /** 每个节点的执行日志 */
    private final List<WorkflowExecutionLog> logs;

    public WorkflowResult(WorkflowState state, String runId, String output,
                          List<WorkflowExecutionLog> logs) {
        this.state = state;
        this.runId = runId;
        this.output = output;
        this.logs = logs;
    }

    /** 把执行日志拼成多行文本，便于展示 */
    public String logsAsText() {
        StringBuilder sb = new StringBuilder();
        logs.forEach(l -> sb.append(l.toReadableLine()).append("\n"));
        return sb.toString();
    }
}