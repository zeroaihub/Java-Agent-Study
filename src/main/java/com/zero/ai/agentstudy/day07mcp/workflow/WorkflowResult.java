package com.zero.ai.agentstudy.day07mcp.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * WorkflowResult —— 一条工作流执行完毕后的统一返回体。
 *
 * <p>教学要点：给调用方一个稳定的结构，既能知道「整体成没成」，又能看到
 * 「实际执行了哪些节点」和「最终产出了什么」，便于观测与调试。</p>
 *
 * @author ZeroAi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowResult {

    /** 工作流名称 */
    private String workflow;

    /** 整体是否成功（所有节点都通过才为 true） */
    private boolean success;

    /** 实际执行到的节点名（按顺序），便于观察在哪一步停下 */
    private List<String> executedNodes;

    /** 失败原因（成功时为 null） */
    private String error;

    /** 最终上下文产出快照 */
    private Map<String, Object> output;

    /**
     * 构造成功结果。
     *
     * @param workflow      工作流名
     * @param executedNodes 已执行节点
     * @param output        产出快照
     * @return 结果
     */
    public static WorkflowResult ok(String workflow,
                                    List<String> executedNodes,
                                    Map<String, Object> output) {
        return WorkflowResult.builder()
                .workflow(workflow)
                .success(true)
                .executedNodes(executedNodes)
                .output(output)
                .build();
    }

    /**
     * 构造失败结果。
     *
     * @param workflow      工作流名
     * @param executedNodes 已执行节点
     * @param error         失败原因
     * @param output        产出快照
     * @return 结果
     */
    public static WorkflowResult fail(String workflow,
                                      List<String> executedNodes,
                                      String error,
                                      Map<String, Object> output) {
        return WorkflowResult.builder()
                .workflow(workflow)
                .success(false)
                .executedNodes(executedNodes)
                .error(error)
                .output(output)
                .build();
    }
}