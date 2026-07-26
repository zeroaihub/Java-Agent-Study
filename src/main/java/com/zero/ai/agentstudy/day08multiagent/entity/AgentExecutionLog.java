package com.zero.ai.agentstudy.day08multiagent.entity;

import com.zero.ai.agentstudy.day08multiagent.agent.core.AgentRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * AgentExecutionLog —— 单个 Agent 一次执行的「审计日志」。
 *
 * <p>教学要点：企业级 Multi-Agent 系统最怕「黑盒」——一篇文章生成了，
 * 但没人知道中间每个 Agent 干了什么、花了多久、成功还是失败。可观测性
 * （Observability）是生产系统的生命线。AgentExecutionLog 就是把「每一步」
 * 都记录下来，最终随响应一起返回，形成一条可追溯的执行链路。</p>
 *
 * <p>它由 {@code AbstractAgent} 在模板方法的 after/onError 阶段自动生成，
 * 业务 Agent 无需关心——这正是模板方法模式带来的「横切关注点统一处理」。</p>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionLog {

    /** 执行序号（第几步，从 1 开始），便于按顺序展示 */
    private int step;

    /** 执行该步的 Agent 角色 */
    private AgentRole role;

    /** 是否成功 */
    private boolean success;

    /** 输入摘要（喂给该 Agent 的关键信息，裁剪后的） */
    private String inputSummary;

    /** 输出摘要（该 Agent 的产出，裁剪后的） */
    private String outputSummary;

    /** 耗时（毫秒） */
    private long costMillis;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 附加说明（失败原因等） */
    private String message;

    /**
     * 便捷工厂：构造一条执行日志。
     *
     * @param step          步骤序号
     * @param role          角色
     * @param success       是否成功
     * @param inputSummary  输入摘要
     * @param outputSummary 输出摘要
     * @param costMillis    耗时
     * @param startTime     开始时间
     * @param message       附加说明
     * @return 日志对象
     */
    public static AgentExecutionLog of(int step, AgentRole role, boolean success,
                                       String inputSummary, String outputSummary,
                                       long costMillis, LocalDateTime startTime, String message) {
        return AgentExecutionLog.builder()
                .step(step).role(role).success(success)
                .inputSummary(inputSummary).outputSummary(outputSummary)
                .costMillis(costMillis).startTime(startTime).message(message)
                .build();
    }
}