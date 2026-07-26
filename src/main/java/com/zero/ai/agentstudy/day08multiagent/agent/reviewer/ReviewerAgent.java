package com.zero.ai.agentstudy.day08multiagent.agent.reviewer;

import com.zero.ai.agentstudy.day08multiagent.agent.core.AbstractAgent;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentContext;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentResult;
import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentRole;
import com.zero.ai.agentstudy.day08multiagent.agent.memory.SharedMemory;
import com.zero.ai.agentstudy.day08multiagent.config.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ReviewerAgent —— 评审者（流水线第 4 棒 / 收尾）。
 *
 * <p>职责（SRP 单一职责）：对 WriterAgent 产出的草稿进行<b>质量评审</b>，给出
 * 「0~1 的分数 + 文字意见」。它不改稿、不重写，只负责「打分 + 提意见」。
 * 分数将作为 Coordinator 判断「是否达标发布 / 是否需要返工」的依据。</p>
 *
 * <p>协作契约（第二章黑板约定）：</p>
 * <ul>
 *   <li><b>读</b>：{@code memory[DRAFT] = String}（上游 Writer 产出）；</li>
 *   <li><b>调</b>：{@link LlmClient#chat(String, String)}，systemPrompt 含「评审/审校」关键字，
 *       MockLlmClient 返回 {@code 分数|意见} 格式；</li>
 *   <li><b>写</b>：{@code memory[SCORE] = Double}、{@code memory[REVIEW] = String}。</li>
 * </ul>
 *
 * <p>解析健壮性：分数解析失败时降级为默认分 0.6，避免因格式异常中断流程。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewerAgent extends AbstractAgent {

    /** 分数解析失败时的兜底默认分 */
    private static final double DEFAULT_SCORE = 0.6;

    /** 可插拔大模型客户端（构造器注入，面向接口编程） */
    private final LlmClient llmClient;

    @Override
    public AgentRole role() {
        return AgentRole.REVIEWER;
    }

    /**
     * 核心业务：读草稿 → 调 LLM 评审 → 解析「分数|意见」→ 写入黑板 SCORE/REVIEW。
     *
     * @param context 协作上下文
     * @return 执行结果（成功携带分数与意见摘要）
     */
    @Override
    protected AgentResult doExecute(AgentContext context) {
        SharedMemory memory = context.getMemory();

        // 1) 防御式读取：草稿必须存在
        String draft = memory.getString(SharedMemory.Keys.DRAFT);
        if (draft == null || draft.isBlank()) {
            return AgentResult.fail(role(), "缺少草稿(DRAFT)，无法评审");
        }

        String systemPrompt = "你是一名严格的内容评审专家，负责审校文章质量。"
                + "请先给出 0~1 的质量分数，再用 | 分隔给出评审意见，格式：分数|意见。";
        String userPrompt = "请评审以下文章：\n" + draft;

        // 2) 调 LLM 评审
        String raw = llmClient.chat(systemPrompt, userPrompt);
        if (raw == null || raw.isBlank()) {
            return AgentResult.fail(role(), "LLM 返回空评审");
        }

        // 3) 解析「分数|意见」（与 MockLlmClient.mockReview 的约定对齐）
        double score = DEFAULT_SCORE;
        String review = raw.trim();
        int sep = raw.indexOf('|');
        if (sep > 0) {
            String scorePart = raw.substring(0, sep).trim();
            review = raw.substring(sep + 1).trim();
            try {
                score = Double.parseDouble(scorePart);
                // 约束在 [0,1] 区间
                score = Math.max(0.0, Math.min(1.0, score));
            } catch (NumberFormatException e) {
                log.warn("[Reviewer] 分数解析失败：{}，降级为默认分 {}", scorePart, DEFAULT_SCORE);
                score = DEFAULT_SCORE;
            }
        }

        // 4) 写回黑板
        memory.put(SharedMemory.Keys.SCORE, score);
        memory.put(SharedMemory.Keys.REVIEW, review);
        log.info("[Reviewer]评审完成，分数={}", score);

        // 5) 返回结果
        return AgentResult.ok(role(), "评审分数=" + score + "，意见：" + review);
    }

    @Override
    protected String summarizeInput(AgentContext context) {
        String draft = context.getMemory().getString(SharedMemory.Keys.DRAFT);
        return "待评审草稿长度=" + (draft == null ? 0 : draft.length());
    }
}