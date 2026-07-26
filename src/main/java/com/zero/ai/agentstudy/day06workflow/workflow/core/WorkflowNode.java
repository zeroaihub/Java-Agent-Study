package com.zero.ai.agentstudy.day06workflow.workflow.core;

import com.zero.ai.agentstudy.day06workflow.workflow.context.WorkflowContext;

/**
 * WorkflowNode —— 工作流「节点」的统一抽象（整个框架的核心接口）。
 *
 * <p>教学要点：这就是「面向接口编程」+「开闭原则(OCP)」的落地。
 * 引擎只依赖这个接口，不依赖任何具体节点。想加新能力（查机票、发邮件），
 * 只需新增一个 implements WorkflowNode 的类，无需改动引擎一行代码。</p>
 *
 * <p>责任链模式的每一环、Pipeline 的每一级，本质都是一个 WorkflowNode。</p>
 *
 * @author ZeroAi
 */
public interface WorkflowNode {

    /**
     * 节点名称，用于日志、可视化、异常定位。
     *
     * @return 唯一且可读的节点名，如 "WeatherNode"
     */
    String name();

    /**
     * 执行节点的核心业务。
     *
     * <p>约定：
     * <ul>
     *   <li>从 {@code context} 读取上游数据；</li>
     *   <li>把自己的产出 put 回 {@code context}；</li>
     *   <li>返回 {@link NodeResult} 告诉引擎结果。</li>
     * </ul>
     * 节点内部不要自己调用下一个节点——那是引擎的职责。</p>
     *
     * @param context 全流程共享的上下文
     * @return 执行回执
     */
    NodeResult execute(WorkflowContext context);

    /**
     * 该节点最大重试次数，默认 0（不重试）。
     * 子类可覆盖，供引擎在第六章的「重试机制」中读取。
     *
     * @return 重试次数
     */
    default int maxRetries() {
        return 0;
    }
}