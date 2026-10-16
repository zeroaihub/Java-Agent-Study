package com.zero.ai.agentstudy.day16knowledgeagentv2.eval;

/**
 * 评估指标端口（Chapter 13）：对单条样本的系统输出打分。
 *
 * <p>可插拔设计：演示版用简单规则，生产可无缝替换为 LLM-as-Judge / RAGAS，
 * 端口不变、实现替换。</p>
 *
 * @author ZeroAi
 */
public interface Metric {

    /** 指标名。 */
    String name();

    /** 对一条样本的输出打分，返回 [0,1]。 */
    double score(EvalSample sample, SystemOutput output);
}