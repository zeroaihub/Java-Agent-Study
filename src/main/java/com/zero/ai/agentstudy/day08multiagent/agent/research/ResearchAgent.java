package com.zero.ai.agentstudy.day08multiagent.agent.research;

import com.zero.ai.agentstudy.day08multiagent.agent.core.AbstractAgent;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentContext;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentResult;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentRole;
import com.zero.ai.agentstudy.day08multiagent.agent.memory.SharedMemory;
import com.zero.ai.agentstudy.day08multiagent.config.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ResearchAgent —— 研究者（流水线第 2 棒）。
 *
 * <p>职责（SRP 单一职责）：根据 PlannerAgent 产出的大纲，为<b>每个小节收集素材</b>
 * （事实、数据、案例），供 WriterAgent 组织成文。它不改大纲、不写正文、不评分。</p>
 *
 * <p>协作契约（第二章黑板约定）：</p>
 * <ul>
 *   <li><b>读</b>：{@code memory[OUTLINE] = List<String>}（上游 Planner 产出）；</li>
 *   <li><b>调</b>：{@link LlmClient#chat(String, String)}，systemPrompt 含「研究/素材」关键字；</li>
 *   <li><b>写</b>：{@code memory[MATERIALS] = Map<String,String>}（小节 -> 素材文本）。</li>
 * </ul>
 *
 * <p>健壮性：若大纲缺失，直接返回失败结果（模板方法会记入日志），
 * 由 Coordinator 决定是否终止流程——体现「防御式读取上游产出」。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearchAgent extends AbstractAgent {

    /** 可插拔大模型客户端（构造器注入，面向接口编程） */
    private final LlmClient llmClient;

    @Override
    public AgentRole role() {
        return AgentRole.RESEARCHER;
    }

    /**
     * 核心业务：读大纲 → 逐节调 LLM 收集素材 → 写入黑板 MATERIALS。
     *
     * @param context 协作上下文
     * @return 执行结果（成功携带素材小节数摘要）
     */
    @Override
    @SuppressWarnings("unchecked")
    protected AgentResult doExecute(AgentContext context) {
        SharedMemory memory = context.getMemory();

        // 1) 防御式读取上游产出：大纲必须存在
        List<String> outline = memory.get(SharedMemory.Keys.OUTLINE, List.class);
        if (outline == null || outline.isEmpty()) {
            return AgentResult.fail(role(), "缺少大纲(OUTLINE)，无法收集素材");
        }

        String topic = context.getTask().getTopic();
        String systemPrompt = "你是一名严谨的研究员，负责为写作大纲收集素材（事实、数据、案例）。"
                + "请围绕给定小节输出可用于写作的要点。";

        // 2) 逐节收集素材，用 LinkedHashMap 保持大纲顺序
        Map<String, String> materials = new LinkedHashMap<>();
        for (String section : outline) {
            String userPrompt = "主题：" + topic + "\n"
                    + "当前小节：" + section + "\n"
                    + "请为该小节收集 2-3 条可用素材。";
            String material = llmClient.chat(systemPrompt, userPrompt);
            materials.put(section, material == null ? "" : material.trim());
        }

        // 3) 写回黑板
        memory.put(SharedMemory.Keys.MATERIALS, materials);
        log.info("[Research] 为 {} 个小节收集素材完成", materials.size());

        // 4) 返回结果
        String output = "素材已收集，覆盖 " + materials.size() + " 个小节";
        return AgentResult.ok(role(), output);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected String summarizeInput(AgentContext context) {
        List<String> outline = context.getMemory().get(SharedMemory.Keys.OUTLINE, List.class);
        return "待研究小节数=" + (outline == null ? 0 : outline.size());
    }
}