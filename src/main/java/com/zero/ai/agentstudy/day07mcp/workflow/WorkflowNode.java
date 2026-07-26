package com.zero.ai.agentstudy.day07mcp.workflow;

/**
 * WorkflowNode —— 工作流节点的统一抽象。
 *
 * <p>教学要点：一条工作流 = 若干节点按顺序执行。把「节点」抽象成一个接口，
 * 好处是 {@link Workflow} 引擎只依赖这个接口，而不关心节点内部到底是调 MCP 工具、
 * 还是做本地计算、还是发通知。想加新步骤，只需再实现一个 WorkflowNode——
 * 这就是开闭原则（OCP）在编排层的体现。</p>
 *
 * <p>每个节点只做一件事（单一职责 SRP）：从上下文读输入 → 干活 → 把产出写回上下文。
 * 节点之间不直接通信，全部通过 {@link WorkflowContext} 这块「黑板」协作，从而解耦。</p>
 *
 * @author ZeroAi
 */
public interface WorkflowNode {

    /**
     * 节点名称（用于日志与调用链追踪）。
     *
     * @return 节点名，如 "query_weather"
     */
    String name();

    /**
     * 执行本节点。
     *
     * <p>约定：</p>
     * <ul>
     *   <li>从 {@code context} 读取所需输入（初始输入或上游节点的产出）；</li>
     *   <li>把自己的产出用 {@code context.put(key, value)} 写回，供下游节点使用；</li>
     *   <li>若本步骤失败（业务失败），应把失败信息写进上下文并返回 false，
     *       由引擎决定是否中断，而不是随意抛异常。</li>
     * </ul>
     *
     * @param context 工作流上下文
     * @return true=本节点成功，可继续；false=本节点失败，引擎应中断
     */
    boolean execute(WorkflowContext context);
}