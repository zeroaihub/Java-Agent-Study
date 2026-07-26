package com.zero.ai.agentstudy.day08multiagent.agent.writer;

import com.zero.ai.agentstudy.day08multiagent.agent.core.AbstractAgent;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentContext;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentResult;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentRole;
import com.zero.ai.agentstudy.day08multiagent.agent.memory.SharedMemory;
import com.zero.ai.agentstudy.day08multiagent.config.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * WriterAgent —— 写作者（流水线第 3 棒）。
 *
 * <p>职责（SRP 单一职责）：整合上游的<b>大纲 + 素材</b>，调用 LLM 产出一篇结构完整的
 * Markdown 正文草稿。它不查素材、不评分，只负责「把材料写成文章」。</p>
 *
 * <p>协作契约（第二章黑板约定）：</p>
 * <ul>
 *   <li><b>读</b>：{@code memory[OUTLINE] = List<String>}、{@code memory[MATERIALS] = Map<String,String>}；</li>
 *   <li><b>调</b>：{@link LlmClient#chat(String, String)}，systemPrompt 含「写作/正文」关键字；</li>
 *   <li><b>写</b>：{@code memory[DRAFT] = String}（Markdown 草稿）。</li>
 * </ul>
 *
 * <p>健壮性：大纲缺失直接失败；素材缺失时降级为「仅凭大纲写作」，保证流程尽量走通。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriterAgent extends AbstractAgent {

    /** 可插拔大模型客户端（构造器注入，面向接口编程） */
    private final LlmClient llmClient;

    @Override
    public AgentRole role() {
        return AgentRole.WRITER;
    }

    /**
     * 核心业务：读大纲+素材 → 拼装提示词 → 调 LLM 成文 → 写入黑板 DRAFT。
     *
     * @param context 协作上下文
     * @return 执行结果（成功携带草稿长度摘要）
     */
    @Override
    @SuppressWarnings("unchecked")
    protected AgentResult doExecute(AgentContext context) {
        SharedMemory memory = context.getMemory();

        // 1) 防御式读取：大纲必须存在
        List<String> outline = memory.get(SharedMemory.Keys.OUTLINE, List.class);
        if (outline == null || outline.isEmpty()) {
            return AgentResult.fail(role(), "缺少大纲(OUTLINE)，无法写作");
        }
        // 素材允许缺失（降级为仅凭大纲写作）
        Map<String, String> materials = memory.get(SharedMemory.Keys.MATERIALS, Map.class);

        String topic = context.getTask().getTopic();

        // 2) 拼装写作提示词：把大纲与素材整理进 userPrompt
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("主题：").append(topic).append("\n");
        userPrompt.append("请根据以下大纲与素材写一篇结构完整的 Markdown 文章：\n");
        for (String section : outline) {
            userPrompt.append("## ").append(section).append("\n");
            if (materials != null && materials.get(section) != null) {
                userPrompt.append("素材：").append(materials.get(section)).append("\n");
            }
        }

        String systemPrompt = "你是一名专业的内容写作者，负责把大纲与素材组织成一篇流畅的 Markdown 正文。"
                + "要求结构清晰、语言通顺、可直接发布。";

        // 3) 调 LLM 成文
        String draft = llmClient.chat(systemPrompt, userPrompt.toString());
        if (draft == null || draft.isBlank()) {
            return AgentResult.fail(role(), "LLM 返回空草稿");
        }

        // 4) 写回黑板
        memory.put(SharedMemory.Keys.DRAFT, draft.trim());
        log.info("[Writer] 生成草稿，长度={} 字符", draft.length());

        // 5) 返回结果
        return AgentResult.ok(role(), "草稿已生成，长度 " + draft.length() + " 字符");
    }

    @Override
    @SuppressWarnings("unchecked")
    protected String summarizeInput(AgentContext context) {
        List<String> outline = context.getMemory().get(SharedMemory.Keys.OUTLINE, List.class);
        Map<String, String> materials = context.getMemory().get(SharedMemory.Keys.MATERIALS, Map.class);
        return "大纲节数=" + (outline == null ? 0 : outline.size())
                + "，素材节数=" + (materials == null ? 0 : materials.size());
    }
}