package com.zero.ai.agentstudy.day17codeagent.metering;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;
import com.zero.ai.agentstudy.day17codeagent.core.common.CodeErrorCode;

/**
 * 任务预算（不可变）。
 *
 * <p>Code Agent 与聊天机器人最大的运维差异：<b>它会自己循环</b>。
 * 一次「改个 bug」的请求，内部可能调 5 次模型、编译 3 轮、跑 8 次测试。
 * 不设预算，一个失控的循环可以在几分钟内烧掉普通人一个月的 API 费用。</p>
 *
 * @param maxTokens        token 上限
 * @param maxModelCalls    模型调用次数上限
 * @param maxRepairRounds  自修复轮次上限
 * @param maxWallClockMs   墙钟时间上限
 * @param pricePerThousandTokens 每千 token 单价（用于估算成本，单位：元）
 * @author ZeroAi
 */
public record TaskBudget(
        int maxTokens,
        int maxModelCalls,
        int maxRepairRounds,
        long maxWallClockMs,
        double pricePerThousandTokens
) {

    public TaskBudget {
        if (maxTokens <= 0 || maxModelCalls <= 0 || maxWallClockMs <= 0) {
            throw new IllegalArgumentException("预算各项必须为正数");
        }
        if (maxRepairRounds < 0) {
            throw new IllegalArgumentException("自修复轮次不能为负");
        }
    }

    /** 教学默认：一次任务最多 20k token、6 次模型调用、3 轮修复、5 分钟。 */
    public static TaskBudget defaults() {
        return new TaskBudget(20_000, 6, 3, 5 * 60 * 1000L, 0.03);
    }

    /** 更严格的交互式场景（要响应快）。 */
    public static TaskBudget interactive() {
        return new TaskBudget(6_000, 3, 1, 60_000L, 0.03);
    }

    /** 估算成本（元）。 */
    public double estimateCost(int tokens) {
        return tokens / 1000.0 * pricePerThousandTokens;
    }
}
