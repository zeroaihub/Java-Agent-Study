package com.zero.ai.agentstudy.day08multiagent.agent.translator;

import com.zero.ai.agentstudy.day08multiagent.agent.core.*;
import com.zero.ai.agentstudy.day08multiagent.agent.memory.SharedMemory;
import com.zero.ai.agentstudy.day08multiagent.config.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranslatorAgent extends AbstractAgent {

    /** 可插拔大模型客户端（构造器注入，面向接口编程） */
    private final LlmClient llmClient;


    @Override
    protected AgentResult doExecute(AgentContext context) throws Exception {
        SharedMemory memory = context.getMemory();
        // 1) 防御式读取：草稿必须存在
        String draft = memory.getString(SharedMemory.Keys.DRAFT);
        if (draft == null || draft.isBlank()) {
            return AgentResult.fail(role(), "缺少草稿(DRAFT)，无法翻译");
        }

        String systemPrompt = "你是一名严格的翻译专家，负责翻译文章。"
                + "格式：翻译结果。";
        String userPrompt = "请翻译以下文章：\n" + draft;

        String response = llmClient.chat(systemPrompt, userPrompt);
        if (response == null || response.isBlank()) {
            return AgentResult.fail(role(), "LLM 返回空大纲");
        }
        // 4) 写回黑板
        memory.put(SharedMemory.Keys.TRANSLATION, response.trim());
        log.info("[Writer] 生成草稿，长度={} 字符", draft.length());


        return AgentResult.ok(role(), "翻译结果已生成，长度 " + response.length() + " 字符");
    }

    @Override
    public AgentRole role() {

        return AgentRole.TRANSLATOR;
    }
}
