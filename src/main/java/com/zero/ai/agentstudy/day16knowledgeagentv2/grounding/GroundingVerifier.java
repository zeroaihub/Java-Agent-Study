package com.zero.ai.agentstudy.day16knowledgeagentv2.grounding;

import com.zero.ai.agentstudy.day16knowledgeagentv2.hybrid.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 接地校验器（Chapter 09 引用 + Chapter 10 接地 + Chapter 11 验证）。
 *
 * <p>三步一体，构成企业问答的「可信闸门」：</p>
 * <ol>
 *     <li><b>引用抽取（Ch09）</b>：为答案匹配支撑它的检索片段，生成 Citation；</li>
 *     <li><b>接地判定（Ch10）</b>：答案里的关键信息是否真的能在检索片段中找到，
 *         没有支撑就是「幻觉」；</li>
 *     <li><b>验证输出（Ch11）</b>：综合引用覆盖率给出置信度，低于阈值则拒答。</li>
 * </ol>
 *
 * <p>生产环境「接地判定」应由 NLI 模型（如 mDeBERTa）判断「片段是否蕴含答案」；此处用
 * 词项重合度做可运行的演示替身，工程结构与真实实现一致。</p>
 *
 * @author ZeroAi
 */
public class GroundingVerifier {

    /** 接地置信度阈值：低于此值视为未接地，触发拒答。 */
    private final double groundThreshold;

    public GroundingVerifier(double groundThreshold) {
        this.groundThreshold = groundThreshold;
    }

    public GroundingVerifier() {
        this(0.3);
    }

    /**
     * 校验答案是否被检索片段支撑，并生成引用。
     *
     * @param answer  LLM 生成的答案
     * @param context 检索到的支撑片段（已经过 ACL 过滤）
     * @return 接地结果（含引用与置信度），未接地时为安全拒答
     */
    public GroundedAnswer verify(String answer, List<RetrievedChunk> context) {
        if (answer == null || answer.isBlank()) {
            return GroundedAnswer.refuse("答案为空");
        }
        if (context == null || context.isEmpty()) {
            return GroundedAnswer.refuse("无检索证据（知识缺口）");
        }

        List<Citation> citations = new ArrayList<>();
        double bestSupport = 0;
        for (RetrievedChunk chunk : context) {
            double support = overlap(answer, chunk.content());
            if (support >= groundThreshold) {
                citations.add(new Citation(
                        chunk.chunkId(), chunk.documentId(), chunk.title(), snippet(chunk.content())));
                bestSupport = Math.max(bestSupport, support);
            }
        }

        if (citations.isEmpty()) {
            return GroundedAnswer.refuse("答案缺乏检索依据（疑似幻觉）");
        }
        return GroundedAnswer.of(answer, citations, bestSupport);
    }

    /** 词项重合度：答案中的词有多少比例出现在片段里（0~1）。 */
    private double overlap(String answer, String content) {
        String[] words = answer.toLowerCase().split("[\\s，。、,.]+");
        if (words.length == 0) {
            return 0;
        }
        String lower = content.toLowerCase();
        long hit = 0;
        for (String w : words) {
            if (w.length() >= 2 && lower.contains(w)) {
                hit++;
            }
        }
        return (double) hit / words.length;
    }

    private String snippet(String content) {
        return content.length() <= 40 ? content : content.substring(0, 40) + "...";
    }
}