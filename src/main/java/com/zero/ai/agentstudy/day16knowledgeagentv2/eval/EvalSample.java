package com.zero.ai.agentstudy.day16knowledgeagentv2.eval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.util.List;

/**
 * 一条评估样本（Chapter 13）：问题 + 标准答案 + 应命中的关键片段。
 *
 * <p>评估集从三个渠道来：① 业务专家手工标注核心问题；② 从线上真实日志采样
 * （尤其是 Ch12 高频点踩的）；③ LLM 辅助生成候选再人工审核。质量 > 数量。</p>
 *
 * @param query            用户问题
 * @param expectedAnswer   标准答案（Ground Truth）
 * @param relevantChunkIds 应被检索到的关键片段 ID
 * @author ZeroAi
 */
public record EvalSample(
        String query,
        String expectedAnswer,
        List<String> relevantChunkIds
) {
    public EvalSample {
        AgentException.requireText(query, "query");
        AgentException.requireText(expectedAnswer, "expectedAnswer");
        relevantChunkIds = (relevantChunkIds == null) ? List.of() : List.copyOf(relevantChunkIds);
    }
}