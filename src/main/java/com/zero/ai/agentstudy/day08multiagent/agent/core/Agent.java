package com.zero.ai.agentstudy.day08multiagent.agent.core;

/**
 * Agent —— 所有智能体的统一能力接口（面向接口设计）。
 *
 * <p>教学要点（SOLID 落地）：</p>
 * <ul>
 *   <li><b>ISP 接口隔离</b>：接口只暴露两个方法——「我是谁角色」和「执行」，
 *       不掺杂任何具体业务，保持最小契约；</li>
 *   <li><b>DIP 依赖倒置</b>：Coordinator 依赖的是 Agent 接口，而不是
 *       PlannerAgent、WriterAgent 等具体类。将来新增/替换 Agent 实现，
 *       Coordinator 一行都不用改；</li>
 *   <li><b>LSP 里氏替换</b>：任何 Agent 实现都能被放进流水线的任意位置，
 *       只要它遵守「输入 AgentContext、输出 AgentResult」这个契约。</li>
 * </ul>
 *
 * <p>标准实现基类是 {@link AbstractAgent}，它用模板方法统一处理了日志、计时、
 * 异常兜底等横切关注点，具体 Agent 只需实现真正的业务逻辑。</p>
 *
 * @author ZeroAi
 */
public interface Agent {

    /**
     * 返回本 Agent 扮演的角色。
     *
     * <p>Coordinator 靠它识别「这是哪个环节的 Agent」，日志靠它标注归属。</p>
     *
     * @return 角色枚举
     */
    AgentRole role();

    /**
     * 执行本 Agent 的职责：从上下文读取所需输入，产出结果并写回共享记忆。
     *
     * <p>约定：</p>
     * <ul>
     *   <li>输入统一来自 {@link AgentContext}（task + 共享记忆）；</li>
     *   <li>产出统一写回 {@code context.getMemory()}，并返回 {@link AgentResult}；</li>
     *   <li><b>不允许</b>把异常抛给调用方——异常必须在内部转成失败的 AgentResult
     *       （由 {@link AbstractAgent} 统一兜底），以免拖垮整个 Coordinator。</li>
     * </ul>
     *
     * @param context 协作上下文（公文包）
     * @return 结构化执行结果
     */
    AgentResult execute(AgentContext context);
}