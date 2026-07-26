package com.zero.ai.agentstudy.day07mcp.workflow;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Workflow —— 顺序编排引擎（把若干 {@link WorkflowNode} 串成一条流水线）。
 *
 * <p>教学要点：这是最基础的编排形态——「顺序执行 + 失败即停」。引擎本身
 * 完全不认识任何具体节点，只依赖 {@link WorkflowNode} 接口（依赖倒置 DIP）。
 * 因此想编排出不同的业务流程，只需往里 add 不同的节点组合，引擎代码一行不改。</p>
 *
 * <p>执行语义：按加入顺序逐个执行节点；任一节点返回 false（业务失败）则中断后续，
 * 把失败原因保留在上下文中返回。每个节点执行前后打日志，形成可追踪的调用链。</p>
 *
 * @author ZeroAi
 */
@Slf4j
public class Workflow {

    /** 工作流名称 */
    private final String name;

    /** 按顺序执行的节点列表 */
    private final List<WorkflowNode> nodes = new ArrayList<>();

    public Workflow(String name) {
        this.name = name;
    }

    /**
     * 追加一个节点（链式调用）。
     *
     * @param node 节点
     * @return this
     */
    public Workflow addNode(WorkflowNode node) {
        nodes.add(node);
        return this;
    }

    /**
     * 执行整条工作流。
     *
     * @param context 上下文
     * @return 执行结果（成功/失败 + 最终产出快照）
     */
    public WorkflowResult run(WorkflowContext context) {
        log.info("[Workflow:{}] 开始执行，共 {} 个节点", name, nodes.size());
        List<String> executed = new ArrayList<>();

        for (WorkflowNode node : nodes) {
            log.info("[Workflow:{}] -> 执行节点: {}", name, node.name());
            boolean ok;
            try {
                ok = node.execute(context);
            } catch (Exception e) {
                // 节点抛出未预期异常：视为流程失败并中断
                log.error("[Workflow:{}] 节点 {} 执行异常", name, node.name(), e);
                return WorkflowResult.fail(name, executed,
                        "节点[" + node.name() + "]执行异常: " + e.getMessage(),
                        context.snapshot());
            }
            executed.add(node.name());

            if (!ok) {
                // 节点业务失败：中断后续
                String reason = context.getString("error");
                log.warn("[Workflow:{}] 节点 {} 业务失败，中断后续。原因: {}",
                        name, node.name(), reason);
                return WorkflowResult.fail(name, executed,
                        reason.isEmpty() ? "节点[" + node.name() + "]失败" : reason,
                        context.snapshot());
            }
        }

        log.info("[Workflow:{}] 全部节点执行完成", name);
        return WorkflowResult.ok(name, executed, context.snapshot());
    }

    /**
     * 工作流名称。
     *
     * @return name
     */
    public String getName() {
        return name;
    }
}