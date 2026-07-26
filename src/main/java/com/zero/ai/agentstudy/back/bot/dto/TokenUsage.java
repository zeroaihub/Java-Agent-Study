package com.zero.ai.agentstudy.back.bot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 使用统计
 *
 * @author ZeroAi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {

    /** 输入token */
    private int promptTokens;

    /** 输出token */
    private int completionTokens;

    /** 总token */
    private int totalTokens;

    /** 累计费用(元) */
    private double costYuan;

    /** 累计调用次数 */
    private int callCount;

    public static TokenUsage empty() {
        return TokenUsage.builder()
                .promptTokens(0).completionTokens(0).totalTokens(0)
                .costYuan(0).callCount(0).build();
    }

    /** 累加一次调用的统计 */
    public TokenUsage accumulate(int prompt, int completion, double cost) {
        this.promptTokens += prompt;
        this.completionTokens += completion;
        this.totalTokens += prompt + completion;
        this.costYuan += cost;
        this.callCount += 1;
        return this;
    }
}
