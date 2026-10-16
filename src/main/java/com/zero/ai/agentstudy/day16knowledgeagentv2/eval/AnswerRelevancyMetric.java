package com.zero.ai.agentstudy.day16knowledgeagentv2.eval;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 答案相关性指标（Chapter 13）：生成答案与标准答案的贴近程度（演示用字符重叠）。
 *
 * <p>「答的是不是用户问的」。生产应替换为语义相似度（embedding 余弦）或 LLM-as-Judge，
 * 端口不变、实现替换。</p>
 *
 * @author ZeroAi
 */
public final class AnswerRelevancyMetric implements Metric {

    @Override
    public String name() {
        return "AnswerRelevancy";
    }

    @Override
    public double score(EvalSample sample, SystemOutput output) {
        return overlapRatio(sample.expectedAnswer(), output.answer());
    }

    /** 期望答案的字符集合被实际答案覆盖的比例。 */
    static double overlapRatio(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return 0.0;
        }
        Set<Character> exp = expected.chars()
                .filter(c -> !Character.isWhitespace(c))
                .mapToObj(c -> (char) c)
                .collect(Collectors.toSet());
        if (exp.isEmpty()) {
            return 0.0;
        }
        Set<Character> act = (actual == null ? "" : actual).chars()
                .mapToObj(c -> (char) c)
                .collect(Collectors.toSet());
        long hit = exp.stream().filter(act::contains).count();
        return (double) hit / exp.size();
    }
}