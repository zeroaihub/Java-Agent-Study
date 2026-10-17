package com.zero.ai.agentstudy.day17codeagent.generator;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;

/**
 * 代码生成请求（不可变）。
 *
 * @param systemPrompt 系统提示词（角色、规则、输出格式契约）
 * @param userPrompt   用户侧内容（RepoMap + 选中代码 + 具体需求）
 * @param maxTokens    本次生成最大 token
 * @param temperature  温度：代码场景建议压到 0.0~0.2
 * @param taskId       任务 ID（写进 trace）
 * @author ZeroAi
 */
public record GenerationRequest(
        String systemPrompt,
        String userPrompt,
        int maxTokens,
        double temperature,
        String taskId
) {

    public GenerationRequest {
        CodeException.requireText(systemPrompt, "systemPrompt");
        CodeException.requireText(userPrompt, "userPrompt");
        if (maxTokens <= 0) {
            throw new CodeException(
                    com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode.INVALID_ARGUMENT,
                    "maxTokens 必须为正数");
        }
        if (temperature < 0.0 || temperature > 2.0) {
            throw new CodeException(
                    com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode.INVALID_ARGUMENT,
                    "temperature 必须在 [0.0, 2.0] 之间");
        }
    }

    public static GenerationRequest of(String system, String user) {
        return new GenerationRequest(system, user, 4096, 0.1, null);
    }

    /** 估算输入 token 数（用于配额预检，无需真正调用模型）。 */
    public int estimateInputTokens() {
        return (int) Math.ceil((systemPrompt.length() + userPrompt.length()) * 0.55);
    }
}
