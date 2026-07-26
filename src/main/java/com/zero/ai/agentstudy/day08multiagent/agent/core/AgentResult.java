package com.zero.ai.agentstudy.day08multiagent.agent.core;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AgentResult —— 单个 Agent 一次执行的「结构化结果」。
 *
 * <p>教学要点：多 Agent 要能「粘」在一起，前提是它们的输出格式统一。
 * 我们规定：无论哪个 Agent，执行完都返回一个 AgentResult。Coordinator 只需看
 * {@code success} 就知道要不要继续、要不要兜底，而不用关心每个 Agent 内部长什么样。
 * 这是 SOLID 中「里氏替换 + 依赖倒置」的体现——面向统一契约编程。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@code role}：产出该结果的 Agent 角色；</li>
 *   <li>{@code success}：本次执行是否成功（失败时 Coordinator 决定重试/降级/跳过）；</li>
 *   <li>{@code output}：核心产出（大纲/素材/草稿/评审的文本摘要，便于日志展示）；</li>
 *   <li>{@code message}：附加说明（失败原因等）；</li>
 *   <li>{@code costMillis}：本次执行耗时（毫秒），用于可观测性。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {

    /** 产出该结果的角色 */
    private AgentRole role;

    /** 是否成功 */
    private boolean success;

    /** 核心产出的文本摘要 */
    private String output;

    /** 附加说明 / 失败原因 */
    private String message;

    /** 本次执行耗时（毫秒） */
    private long costMillis;

    /**
     * 构造成功结果。
     *
     * @param role   角色
     * @param output 产出摘要
     * @return 结果
     */
    public static AgentResult ok(AgentRole role, String output) {
        return AgentResult.builder()
                .role(role)
            .success(true)
                .output(output)
                .message("ok")
                .build();
    }

    /**
     * 构造失败结果。
     *
     * @param role    角色
     * @param message 失败原因
     * @return 结果
     */
    public static AgentResult fail(AgentRole role, String message) {
        return AgentResult.builder()
                .role(role)
                .success(false)
                .message(message)
                .build();
    }
}