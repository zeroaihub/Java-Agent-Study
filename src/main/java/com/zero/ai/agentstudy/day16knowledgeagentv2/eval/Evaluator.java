package com.zero.ai.agentstudy.day16knowledgeagentv2.eval;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentErrorCode;
import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 评估器（Chapter 13）：对整个评估集逐样本、逐指标打分，汇总成报告。
 *
 * <p>每个指标是一个可插拔的 {@link Metric} 端口，评估器只负责编排与汇总。
 * 支持新旧版本对比——把「我觉得变好了」变成「数据证明变好了」，作为上线前的守门员。</p>
 *
 * @author ZeroAi
 */
public final class Evaluator {

    private final List<Metric> metrics;

    public Evaluator(List<Metric> metrics) {
        AgentException.requireNonNull(metrics, "metrics");
        AgentException.requireState(!metrics.isEmpty(),
                AgentErrorCode.INVALID_ARGUMENT, "至少需要一个指标");
        this.metrics = List.copyOf(metrics);
    }

    /**
     * 对评估集跑分。
     *
     * @param dataset 评估集
     * @param system  被评估系统：给定样本产出系统输出
     * @return 各指标的平均分（保持指标注册顺序）
     */
    public Map<String, Double> evaluate(List<EvalSample> dataset,
                                        Function<EvalSample, SystemOutput> system) {
        AgentException.requireNonNull(dataset, "dataset");
        AgentException.requireState(!dataset.isEmpty(),
                AgentErrorCode.EVALUATION_FAILED, "评估集不能为空");

        Map<String, Double> sum = new LinkedHashMap<>();
        for (Metric m : metrics) {
            sum.put(m.name(), 0.0);
        }
        for (EvalSample sample : dataset) {
            SystemOutput out = system.apply(sample);
            for (Metric m : metrics) {
                sum.merge(m.name(), m.score(sample, out), Double::sum);
            }
        }
        Map<String, Double> avg = new LinkedHashMap<>();
        int n = dataset.size();
        sum.forEach((k, v) -> avg.put(k, v / n));
        return avg;
    }
}