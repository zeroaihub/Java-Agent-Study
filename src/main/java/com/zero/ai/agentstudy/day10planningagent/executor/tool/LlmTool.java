package com.zero.ai.agentstudy.day10planningagent.executor.tool;

import com.zero.ai.agentstudy.day10planningagent.context.PlanningContext;
import com.zero.ai.agentstudy.day10planningagent.core.PlanStep;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * LLM 工具：承担理解、提取、筛选、总结、排版等推理型步骤。
 * 把「目标 + 当前步骤 + 已完成上下文」喂给模型。
 */
@Component
public class LlmTool implements Tool {

    private final ChatClient chatClient;

    public LlmTool(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String name() { return "llm"; }

    @Override
    public String description() { return "使用大模型完成理解/提取/筛选/总结/排版等推理型任务"; }

    @Override
    public String execute(PlanStep step, PlanningContext ctx) throws Exception {
        String prompt = """
                你正在执行一个多步骤任务中的一步。请只完成当前步骤，输出简洁可用的结果。

                总体目标：%s

                当前步骤：%s

                已完成步骤的成果（可作为输入）：
                %s

                请直接输出本步骤的结果内容，不要多余解释。
                """.formatted(
                ctx.goal().description(),
                step.action(),
                ctx.completedSummary().isBlank() ? "（暂无）" : ctx.completedSummary());

        String result = chatClient.prompt().user(prompt).call().content();
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("LLM 返回空结果");
        }
        return result;
    }
}