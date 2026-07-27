package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;

/**
 * 工具抽象。可插拔：任何 @Component 实现都会被 ToolRegistry 自动收录。
 */
public interface Tool {

    /** 工具唯一名称（如 browser、llm）。 */
    String name();

    /** 工具能力描述，供 ToolSelector 与提示词使用。 */
    String description();

    /**
     * 执行步骤。允许抛异常，由 StepExecutor 统一捕获并触发重试。
     * @return 执行输出
     */
    String execute(PlanStep step, PlanningContext ctx) throws Exception;
}