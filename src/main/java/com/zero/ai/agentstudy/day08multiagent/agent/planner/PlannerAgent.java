package com.zero.ai.agentstudy.day08multiagent.agent.planner;

import com.zero.ai.agentstudy.day08multiagent.agent.core.AbstractAgent;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentContext;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentResult;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentRole;
import com.zero.ai.agentstudy.day08multiagent.agent.memory.SharedMemory;
import com.zero.ai.agentstudy.day08multiagent.config.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * PlannerAgent —— 规划者（流水线第 1 棒）。
 *
 * <p>职责（SRP 单一职责）：只做一件事——把用户的「模糊需求」拆解成一份<b>可执行的写作大纲</b>，
 * 供下游的 ResearchAgent / WriterAgent 使用。它不写正文、不查素材、不评分。</p>
 *
 * <p>协作契约（第二章黑板约定）：</p>
 * <ul>
 *   <li><b>读</b>：{@code context.task.topic / requirement}（用户原始诉求）；</li>
 *   <li><b>调</b>：{@link LlmClient#chat(String, String)}，systemPrompt 含「规划」关键字，
 *       MockLlmClient 会返回用 {@code |||} 分隔的大纲；</li>
 *   <li><b>写</b>：{@code memory[OUTLINE] = List<String>}（大纲小节列表）。</li>
 * </ul>
 *
 * <p>可插拔：{@code LlmClient} 由构造器注入（DIP 依赖倒置），换真实模型无需改本类。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent extends AbstractAgent {

    /** 可插拔大模型客户端（构造器注入，面向接口编程） */
    private final LlmClient llmClient;

    @Override
    public AgentRole role() {
        return AgentRole.PLANNER;
    }

    /**
     * 核心业务：调用 LLM 生成大纲 → 按 {@code |||} 拆分 → 写入黑板 OUTLINE。
     *
     * @param context 协作上下文
     * @return 执行结果（成功携带大纲摘要）
     */
    @Override
    protected AgentResult doExecute(AgentContext context) {
        String topic = context.getTask().getTopic();
        String requirement = context.getTask().getRequirement();

        // 1) 构造提示词：systemPrompt 携带「规划」关键字，用于 MockLlmClient 分流
        String systemPrompt = "你是一名资深内容规划师，负责把用户需求拆解成清晰的写作大纲。"
                + "请只输出大纲，各小节之间用 ||| 分隔，不要输出多余解释。";
        String userPrompt = "主题：" + topic + "\n"
                + "写作要求：" + (requirement == null ? "无特殊要求" : requirement) + "\n"
                + "请输出 5 个左右的大纲小节。";

        // 2) 调用大模型
        String raw = llmClient.chat(systemPrompt, userPrompt);
        if (raw == null || raw.isBlank()) {
            return AgentResult.fail(role(), "LLM 返回空大纲");
        }

        // 3) 解析：按 ||| 拆分成小节列表（与 MockLlmClient.mockPlan 的约定对齐）
        List<String> outline = Arrays.stream(raw.split("\\|\\|\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (outline.isEmpty()) {
            return AgentResult.fail(role(), "大纲解析后为空");
        }

        // 4) 写回黑板，供下游 Agent 使用
        context.getMemory().put(SharedMemory.Keys.OUTLINE, outline);
       log.info("[Planner] 生成大纲 {} 个小节", outline.size());

        // 5) 返回结构化结果
        String output = "大纲(" + outline.size() + "节)：" + String.join(" / ", outline);
        return AgentResult.ok(role(), output);
    }

    @Override
    protected String summarizeInput(AgentContext context) {
        return "规划主题=" + context.getTask().getTopic();
    }
}