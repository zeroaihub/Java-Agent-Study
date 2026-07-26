package com.zero.ai.agentstudy.day08multiagent.config;

/**
 * LlmClient —— 「调用大模型」的能力接口（可插拔设计）。
 *
 * <p>教学要点（SOLID·DIP 依赖倒置）：四个 Agent 内部都需要调大模型，但它们
 * <b>不应该</b>关心背后是 OpenAI、通义、Ollama 还是一个 Mock。因此我们把「调模型」
 * 抽成接口，Agent 依赖接口而非具体实现。带来的好处：</p>
 * <ul>
 *   <li><b>开箱即运行</b>：默认注入 {@link MockLlmClient}，无需任何 API Key 即可跑通全流程；</li>
 *   <li><b>可切换</b>：配置真实 Key 后，换一个实现类即可接入真实大模型，Agent 代码零改动；</li>
 *   <li><b>可测试</b>：单元测试时注入假的 LlmClient，稳定可控。</li>
 * </ul>
 *
 * <p>实现类：</p>
 * <ul>
 *   <li>{@link MockLlmClient} —— 默认实现，基于规则模拟输出（教学/离线用）；</li>
 *   <li>（未来）OpenAiLlmClient —— 接真实大模型（配置 Key 后启用）。</li>
 * </ul>
 *
 * @author ZeroAi
 */
public interface LlmClient {

    /**
     * 把一段完整 Prompt 发给大模型，返回生成文本。
     *
     * @param systemPrompt 系统提示（角色设定，如「你是一名严谨的技术编辑」）
     * @param userPrompt   用户提示（本次具体任务与输入）
     * @return 模型生成的文本
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 返回当前实现的名称，便于日志区分「用的是 Mock 还是真实模型」。
     *
     * @return 实现名称
     */
    String name();
}