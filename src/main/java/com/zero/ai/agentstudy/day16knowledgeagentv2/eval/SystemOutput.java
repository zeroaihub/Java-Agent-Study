package com.zero.ai.agentstudy.day16knowledgeagentv2.eval;

import java.util.List;

/**
 * 系统对一条样本的实际输出（Chapter 13）：检索到的片段 ID + 生成的答案。
 *
 * @param retrievedChunkIds 检索命中的片段 ID
 * @param answer            生成的答案
 * @author ZeroAi
 */
public record SystemOutput(
        List<String> retrievedChunkIds,
        String answer
) {
    public SystemOutput {
        retrievedChunkIds = (retrievedChunkIds == null) ? List.of() : List.copyOf(retrievedChunkIds);
        answer = (answer == null) ? "" : answer;
    }
}