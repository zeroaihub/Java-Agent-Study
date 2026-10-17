package com.zero.ai.agentstudy.day17codeagent.generator;

/**
 * 代码生成结果。
 *
 * @param text            模型原始输出（未解析）
 * @param promptTokens    输入 token
 * @param completionTokens 输出 token
 * @param elapsedMs       耗时
 * @param modelId         实际执行的模型
 * @param fallback        是否发生了降级（例如超时后回退到备选模型）
 * @author ZeroAi
 */
public record GenerationOutcome(
        String text,
        int promptTokens,
        int completionTokens,
        long elapsedMs,
        String modelId,
        boolean fallback
) {

    public static GenerationOutcome of(String text, String modelId, int promptTokens,
                                       int completionTokens, long elapsedMs) {
        return new GenerationOutcome(text, promptTokens, completionTokens, elapsedMs, modelId, false);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    @Override
    public String toString() {
        return "GenerationOutcome{model=" + modelId
                + ", token=" + promptTokens + "+" + completionTokens
                + ", " + elapsedMs + "ms, chars=" + (text == null ? 0 : text.length()) + "}";
    }
}
