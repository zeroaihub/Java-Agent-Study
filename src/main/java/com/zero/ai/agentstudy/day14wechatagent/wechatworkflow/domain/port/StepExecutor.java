package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.ExecutionContext;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.StepResult;
import com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model.WorkflowStep;

/**
 * 步骤执行器端口（Workflow 的「手」）。
 *
 * <p>Planner 负责「想」（规划出步骤），StepExecutor 负责「做」（真正执行某类动作）。
 * 每个执行器只处理自己认领的 action 类型，引擎按 action 把步骤路由到匹配的执行器。</p>
 *
 * <p>典型实现（本模块用 Mock 演示，生产期替换为真实适配器）：</p>
 * <ul>
 *     <li>{@code office.summarize} —— 调 Office Agent 生成会议纪要</li>
 *     <li>{@code browser.upload}   ——调 Browser Agent 上传文件</li>
 *     <li>{@code knowledge.save}   —— 存入企业知识库（RAG）</li>
 *     <li>{@code notify.send}      —— 推送企业微信消息</li>
 * </ul>
 *
 * <p>采用「多实现 + supports 路由」的策略模式：新增一类动作只需新增一个执行器，
 * 无需改动引擎（开闭原则）。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public interface StepExecutor {

    /**
     * 是否支持该 action 类型。
     *
     * <p>引擎遍历所有执行器，选第一个 {@code supports} 返回 true 的来执行步骤。</p>
     *
     * @param action 步骤动作标识，如 {@code office.summarize}
     * @return 是否认领该动作
     */
    boolean supports(String action);

    /**
     * 执行步骤。
     *
     * <p>实现约定：</p>
     * <ul>
     *     <li>从 {@code context} 黑板读取前序步骤产出作为输入。</li>
     *     <li>把本步产出写回黑板（供后续步骤消费），并作为摘要放进返回值。</li>
     *     <li>用 {@link StepResult} 显式回报成功/失败/跳过，而非抛异常。</li>
     * </ul>
     *
     * @param step    待执行的步骤
     * @param context 执行上下文（黑板 + 元信息）
     * @return 执行结果
     */
    StepResult execute(WorkflowStep step, ExecutionContext context);
}