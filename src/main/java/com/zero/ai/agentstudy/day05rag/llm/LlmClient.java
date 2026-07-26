package com.zero.ai.agentstudy.day05rag.llm;

/**
 * LlmClient —— 「调用大模型对话」的能力接口（面向接口设计）。
 *
 * <p>为什么抽成接口：调大模型有多种后端——云端 API(OpenAI/通义)、本地(Ollama)、
 * 离线降级。上层 RAG 主流程不该关心用哪种。抽成接口后可随意替换实现，
 * 也便于测试(注入假的 LlmClient)。</p>
 *
 * <p>实现类：</p>
 * <ul>
 *   <li>{@link EchoLlmClient} —— 离线降级实现(本章教学用，不真正调模型)</li>
 *   <li>（未来）OpenAiLlmClient —— 云端 API 实现</li>
 *   <li>（未来）OllamaLlmClient —— 本地模型实现</li>
 * </ul>
 *
 * @author ZeroAi
 */
public interface LlmClient {

    /**
     * 把一段完整 Prompt 发给大模型，返回生成的答案。
     *
     * @param prompt 已由 PromptBuilder 组装好的提示词(含角色/约束/资料/问题)
     * @return 模型生成的回答文本
     */
    String chat(String prompt);
}