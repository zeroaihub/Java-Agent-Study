package com.zero.ai.agentstudy.day16knowledgeagentv2.eval;

import java.util.HashSet;
import java.util.Set;

/**
 * 命中率指标（Chapter 13）：相关片段被检索到的比例，衡量检索质量。
 *
 * <p>「该找的资料找到了吗」。若命中率就低，病根在检索，优化生成是白费力气。</p>
 *
 * @author ZeroAi
 */
public final class HitRateMetric implements Metric {

    @Override
    public String name() {
        return "HitRate";
    }

    @Override
    public double score(EvalSample sample, SystemOutput output) {
        var relevant = sample.relevantChunkIds();
        if (relevant.isEmpty()) {
            return 1.0; // 无相关性要求，视为满分
        }
        Set<String> retrieved = new HashSet<>(output.retrievedChunkIds());
        long hit = relevant.stream().filter(retrieved::contains).count();
        return (double) hit / relevant.size();
    }
}