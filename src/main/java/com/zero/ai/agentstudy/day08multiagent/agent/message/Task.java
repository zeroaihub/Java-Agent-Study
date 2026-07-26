package com.zero.ai.agentstudy.day08multiagent.agent.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * Task —— 一次多 Agent 协作要完成的「总任务」。
 *
 * <p>教学要点：Task 是用户需求进入系统后的第一个标准化载体。
 * Coordinator 拿到 Task 后启动整条流水线，各 Agent 都能从 {@code AgentContext} 里
 * 读到同一个 Task，从而知道「我们到底在为什么目标工作」。</p>
 *
 * <p>本项目的 Task 面向「内容生产」：核心是主题(topic) + 额外要求(requirement)。</p>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Task {

    /** 任务唯一 ID，用于全链路日志追踪 */
    private String taskId;

    /** 文章主题，如「2024 年最值得推荐的 AI 编程工具」 */
    private String topic;

    /** 额外要求，如「面向 Java 初学者，字数 800 字以内，风格轻松」 */
    private String requirement;

    /**
     * 便捷工厂：根据主题与要求创建任务，自动生成 taskId。
     *
     * @param topic       文章主题
     * @param requirement 额外要求（可为空）
     * @return 任务对象
     */
    public static Task of(String topic, String requirement) {
        return Task.builder()
                .taskId(UUID.randomUUID().toString().substring(0, 8))
                .topic(topic)
                .requirement(requirement == null ? "" : requirement)
                .build();
    }
}