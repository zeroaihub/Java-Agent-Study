package com.zero.ai.agentstudy.day05rag.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * EchoLlmClient —— 离线降级的 LLM 实现（教学演示用，不真正调用大模型）。
 *
 * <p>为什么存在：训练营演示环境可能无网络/无 API Key，无法调真模型。本类不产生
 * 真正的智能，而是<b>解析 PromptBuilder 拼好的 Prompt</b>，模拟一个「遵守约束」的
 * 助手行为：</p>
 * <ul>
 *   <li>若资料区为空(含「（无相关资料）」) → 回答「未在资料中找到相关信息」(兜底)</li>
 *   <li>若有资料 → 把资料条目整理成「根据资料[i]...」的可读答案，并标注引用</li>
 * </ul>
 *
 * <p><b>局限</b>：它不会真正理解问题、不会归纳推理，只演示「资料→Prompt→答案」链路
 * 能跑通。真实智能需换成云端/本地大模型实现——面向接口，主流程一行不改。</p>
 *
 * @author ZeroAi
 */
public class EchoLlmClient implements LlmClient {

    /** 资料区起始标记，与 PromptBuilder 约定一致 */
    private static final String CONTEXT_MARK = "【资料】";

    /** 问题区起始标记 */
    private static final String QUESTION_MARK = "【问题】";

    /** 资料为空时的占位符，与 PromptBuilder 约定一致 */
    private static final String EMPTY_CONTEXT = "（无相关资料）";

    /** 兜底话术 */
    private static final String FALLBACK = "未在资料中找到相关信息。";

    @Override
    public String chat(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return FALLBACK;
        }
        // 截取【资料】与【问题】之间的资料区文本
        int ctxStart = prompt.indexOf(CONTEXT_MARK);
        int qStart = prompt.indexOf(QUESTION_MARK);
        if (ctxStart < 0 || qStart < 0 || qStart <= ctxStart) {
            return FALLBACK;
        }
        String contextBlock =
                prompt.substring(ctxStart + CONTEXT_MARK.length(), qStart).trim();

        // 兜底：资料为空
        if (contextBlock.isEmpty() || contextBlock.contains(EMPTY_CONTEXT)) {
            return FALLBACK;
        }

        // 抽取每条以 "[编号]" 开头的资料行
        List<String> facts = new ArrayList<>();
        for (String line : contextBlock.split("\n")) {
            String s = line.trim();
            if (s.startsWith("[")) {
                facts.add(s);
            }
        }
        if (facts.isEmpty()) {
            return FALLBACK;
        }

        // 基于资料组织一个「遵守约束、标注引用」的可读答案
        StringBuilder answer = new StringBuilder("根据资料，");
        for (int i = 0; i < facts.size(); i++) {
            // 去掉行首编号，保留正文；引用编号用[i+1] 标注
            String fact = facts.get(i);
            int close = fact.indexOf(']');
            String body = close >= 0 ? fact.substring(close + 1).trim() : fact;
            answer.append(body).append("[").append(i + 1).append("]");
            answer.append(i == facts.size() - 1 ? "。" : "；");
        }
        return answer.toString();
    }

    /**
     * 演示入口：直接喂两种 Prompt，观察「有资料」与「资料为空」两种回答。
     */
    public static void main(String[] args) {
        LlmClient llm = new EchoLlmClient();

        String withCtx = "你是助手。\n【资料】\n[1] 员工每年享有10天带薪年假（来源:员工手册.pdf）\n"
                + "[2] 试用期为3个月，转正后年假增加（来源:员工手册.pdf）\n\n【问题】员工每年有几天年假？\n";
        System.out.println("有资料 → " + llm.chat(withCtx));

        String emptyCtx = "你是助手。\n【资料】\n（无相关资料）\n\n【问题】上班可以带宠物吗？\n";
        System.out.println("资料为空 → " + llm.chat(emptyCtx));
    }
}