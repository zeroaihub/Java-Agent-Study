package com.zero.ai.agentstudy.day06workflow.workflow.engine;

import com.zero.ai.agentstudy.day06workflow.workflow.context.ContextKeys;
import com.zero.ai.agentstudy.day06workflow.workflow.context.WorkflowContext;
import com.zero.ai.agentstudy.day06workflow.workflow.core.NodeResult;
import com.zero.ai.agentstudy.day06workflow.workflow.core.NodeStatus;
import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowNode;
import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowState;
import com.zero.ai.agentstudy.day06workflow.workflow.model.WorkflowExecutionLog;
import com.zero.ai.agentstudy.day06workflow.workflow.model.WorkflowResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * WorkflowEngine —— 工作流执行引擎（整个框架的「发动机」）。
 *
 * <p>教学要点（第五章核心）：引擎与业务彻底解耦。它只认识
 * {@link WorkflowNode} 接口，按顺序驱动一串节点执行，
 * 根据每个节点返回的 {@link NodeResult} 决定继续 / 结束 / 判失败。
 * 这正是「责任链 + 状态机」的组合：
 * <ul>
 *   <li>责任链：节点依次串联执行；</li>
 *   <li>状态机：流程宏观状态 CREATED→RUNNING→COMPLETED/FAILED。</li>
 * </ul></p>
 *
 * <p>第六章增强能力都收敛在这里：重试(retry)、耗时统计、执行日志。
 * 加这些能力不需要改任何 Node——OCP 的体现。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class WorkflowEngine {

    /**
     * 执行一条由若干节点组成的工作流。
     *
     *@param nodes   有序节点列表（责任链）
     * @param context 共享上下文
     * @return 运行总结果
     */
    public WorkflowResult run(List<WorkflowNode> nodes, WorkflowContext context) {
        List<WorkflowExecutionLog> logs = new ArrayList<>();
        context.setState(WorkflowState.RUNNING);
        log.info("[Engine-{}] 流程开始，共 {} 个节点", context.getRunId(), nodes.size());

        for (WorkflowNode node : nodes) {
            NodeResult result = executeWithRetry(node, context, logs);

            // 失败：终止流程
            if (result.getStatus() == NodeStatus.FAILED) {
                context.setState(WorkflowState.FAILED);
                log.error("[Engine-{}] 节点 {} 失败，流程终止: {}",
                        context.getRunId(), node.name(), result.getMessage());
                return buildResult(context, logs);
            }
            // 挂起：等待人工，暂停流程
            if (result.getStatus() == NodeStatus.SUSPENDED) {
                context.setState(WorkflowState.SUSPENDED);
                log.warn("[Engine-{}] 节点 {} 挂起，等待人工介入",
                        context.getRunId(), node.name());
                return buildResult(context, logs);
            }
            // 正常结束
            if (result.getStatus() == NodeStatus.COMPLETED) {
                context.setState(WorkflowState.COMPLETED);
                log.info("[Engine-{}] 流程正常结束于节点 {}",
                        context.getRunId(), node.name());
                return buildResult(context, logs);
            }
            // SUCCESS：推进到下一节点
            context.advance();
        }

        // 所有节点跑完（末节点未显式 completed 也算完成）
        context.setState(WorkflowState.COMPLETED);
        return buildResult(context, logs);
    }

    /**
     * 带重试地执行单个节点，并记录执行日志。
     *
     * @param node    节点
     * @param context 上下文
     * @param logs    日志累加列表
     * @return 该节点最终结果
     */
    private NodeResult executeWithRetry(WorkflowNode node, WorkflowContext context,
                                        List<WorkflowExecutionLog> logs) {
        int maxRetries = node.maxRetries();
        int attempt = 0;
        long start = System.currentTimeMillis();
        NodeResult result;

        while (true) {
            try {
                log.info("[Engine-{}] 执行节点 {} (第 {} 次)",
                        context.getRunId(), node.name(), attempt + 1);
                result = node.execute(context);
            } catch (Exception e) {
                // 节点抛异常，包装成失败结果，防止整个引擎崩溃
                result = NodeResult.fail("节点执行异常: " + e.getMessage(), e);
            }

            if (result.getStatus() != NodeStatus.FAILED || attempt >= maxRetries) {
                break;
            }
            attempt++;
            log.warn("[Engine-{}] 节点 {} 失败，重试 {}/{}",
                    context.getRunId(), node.name(), attempt, maxRetries);
            try {
                Thread.sleep((long) Math.pow(2, attempt) * 100);
            } catch (InterruptedException e) {
                log.warn("[Engine-{}] 节点 {} 失败，等待失败",
                        context.getRunId(), node.name());
            }
        }

        long cost = System.currentTimeMillis() - start;
        logs.add(new WorkflowExecutionLog(node.name(), result.getStatus(),
                result.getMessage(), attempt, cost));
        return result;
    }

    private WorkflowResult buildResult(WorkflowContext context,
                                       List<WorkflowExecutionLog> logs) {
        String output = context.getString(ContextKeys.OUTPUT);
        return new WorkflowResult(context.getState(), context.getRunId(), output, logs);
    }
}