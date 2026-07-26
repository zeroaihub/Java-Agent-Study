package com.zero.ai.agentstudy.day08multiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ContentRequest —— 「内容生产」接口的入参 DTO。
 *
 * <p>教学要点：DTO（Data Transfer Object）是 Controller 层与外界交互的契约，
 * 与内部领域对象（Task）解耦。前端只关心「主题 + 要求」，无需了解内部 Task 结构。</p>
 *
 * @author ZeroAi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentRequest {

    /** 文章主题（必填），如「2024 年最值得推荐的 AI 编程工具」 */
    private String topic;

    /** 额外要求（可选），如「面向 Java 初学者，字数 800 字以内」 */
    private String requirement;
}