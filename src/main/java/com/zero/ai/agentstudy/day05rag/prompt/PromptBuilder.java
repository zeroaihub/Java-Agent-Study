package com.zero.ai.agentstudy.day05rag.prompt;

import com.zero.ai.agentstudy.day05rag.entity.SearchResult;
import com.zero.ai.agentstudy.day05rag.entity.Chunk;

import java.util.List;

/**
 * PromptBuilder —— 增强生成的「提示词组装器」。
 *
 * <p>职责：把「召回片段(List<SearchResult>)」+「用户问题」拼成一段结构化 Prompt，
 * 明确约束 LLM「只依据资料回答、资料没有就说未找到、可标注引用编号」。</p>
 *
 * <p>Prompt 四段结构：① 角色指令 ② 约束(防幻觉护栏) ③ 资料(编号+来源) ④ 问题。</p>
 *
 * <p>本类还做了简化版 token 预算：按得分顺序拼资料，累计字符超过上限就停，
 * 避免 Context 无限膨胀撑爆 LLM 上下文窗口。</p>
 *
 * @author ZeroAi
 */
public class PromptBuilder {

    /** 资料区总字符预算(简化版 token 控制)，超出则不再拼后续片段 */
    private static final int DEFAULT_CONTEXT_CHAR_BUDGET = 2000;

    /** 系统角色 + 约束指令：RAG 防幻觉的核心护栏 */
    private static final String SYSTEM_INSTRUCTION =
            "你是企业知识库助手。请严格遵守以下规则：\n"
                    + "1. 只能依据下方【资料】中的内容回答，不得使用资料之外的知识；\n"
                    + "2. 若【资料】中没有相关信息，请直接回答：未在资料中找到相关信息；\n"
                    + "3. 回答时可用 [编号] 标注你引用了哪条资料。\n";

    private final int contextCharBudget;

    /** 使用默认字符预算构造 */
    public PromptBuilder() {
        this(DEFAULT_CONTEXT_CHAR_BUDGET);
    }

    /**
     * 自定义资料区字符预算构造。
     *
     * @param contextCharBudget 资料区最大字符数，必须 > 0
     */
    public PromptBuilder(int contextCharBudget) {
        if (contextCharBudget <= 0) {
            throw new IllegalArgumentException("contextCharBudget 必须大于 0");
        }
        this.contextCharBudget = contextCharBudget;
    }

    /**
     * 组装 RAG Prompt。
     *
     * @param question 用户问题
     * @param results  第六章 Retriever 召回的相关片段(可能为空)
     * @return 拼好的完整 Prompt 字符串
     */
    public String build(String question, List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        // ① 角色 + ② 约束
        sb.append(SYSTEM_INSTRUCTION).append("\n");

        // ③ 资料区
        sb.append("【资料】\n");
        if (results == null || results.isEmpty()) {
            // 召回为空：明确告诉模型资料为空，触发兜底回答
            sb.append("（无相关资料）\n");
        } else {
            int used = 0;   // 已拼字符数(简化 token 预算)
            int idx = 1;    // 资料编号
            for (SearchResult r : results) {
                String content = r.getChunk().getContent();
                Object source = r.getChunk().getMetadata().get("source");
                String line = "[" + idx + "] " + content
                        + (source != null ? "（来源:" + source + "）" : "") + "\n";
                // 超预算则停止拼接，保护上下文窗口
                if (used + line.length() > contextCharBudget) {
                    break;
                }
                sb.append(line);
                used += line.length();
                idx++;
            }
        }

        // ④ 问题
        sb.append("\n【问题】").append(question == null ? "" : question).append("\n");
        return sb.toString();
    }

    /**
     * 演示入口：直观感受拼好的 Prompt 结构，以及召回为空时的形态。
     */
    public static void main(String[] args) {
        // 构造两条模拟召回结果
        Chunk c1 =
                new Chunk(
                        "doc-0", "员工每年享有10天带薪年假", new float[]{1f})
                        .addMetadata("source", "员工手册.pdf");
        Chunk c2 =
                new Chunk(
                        "doc-2", "试用期为3个月，转正后年假增加", new float[]{1f})
                        .addMetadata("source", "员工手册.pdf");
        List<SearchResult> results = List.of(
                new SearchResult(c1, 0.87),
                new SearchResult(c2, 0.61));

        PromptBuilder builder = new PromptBuilder();

        System.out.println("===== 有召回结果的 Prompt =====");
        System.out.println(builder.build("员工每年有几天年假？", results));

        System.out.println("===== 召回为空的 Prompt(触发兜底) =====");
        System.out.println(builder.build("上班可以带宠物吗？", java.util.Collections.emptyList()));
    }
}