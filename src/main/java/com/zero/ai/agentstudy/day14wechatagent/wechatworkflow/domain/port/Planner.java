package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowTask;

/**
 * 规划器端口（Planning Agent 的领域抽象）。
 *
 * <p>职责：把一句<strong>自然语言目标</strong>（如「帮我整理今天所有会议内容，生成纪要并发给项目组」）
 * 自动分解为一个<strong>有序的、可执行的 {@link WorkflowTask}</strong>（内含若干 WorkflowStep）。</p>
 *
 * <p>这是「Planning Agent」的核心能力。为什么要抽象成端口？</p>
 * <ul>
 *     <li>教学/测试期：用规则/关键词分解（KeywordPlanner），零依赖、可预测。</li>
 *     <li>生产期：替换为 LLM function-calling / ReAct 规划器，让大模型输出结构化步骤 JSON。</li>
 * </ul>
 *
 * <p><strong>领域端口不依赖任何 Spring 注解</strong>，实现类才在 infrastructure 层加 {@code @Component}。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public interface Planner {

    /**
     * 将自然语言目标分解为可执行的工作流任务。
     *
     * <p>实现约定：</p>
     * <ul>
     *     <li>必须返回一个已装配好 steps 的 {@link WorkflowTask}（状态为 CREATED）。</li>
     * <li>steps 顺序即执行顺序；需要人工放行的步骤应设 {@code requiresApproval=true}。</li>
     *     <li>不应真正执行任何业务动作——规划与执行严格分离。</li>
     * </ul>
     *
     * @param goal    自然语言目标，不能为空
     * @param context 执行上下文（携带 tenantId/userId 等元信息，供规划参考）
     * @return 装配好步骤的工作流任务
     */
    WorkflowTask plan(String goal, ExecutionContext context);
}