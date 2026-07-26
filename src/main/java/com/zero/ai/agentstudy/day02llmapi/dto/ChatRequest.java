package com.zero.ai.agentstudy.day02llmapi.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Day02 聊天请求 DTO。
 * <p>
 * 承载用户输入与可选控制参数，配合 Controller 的 {@code @Valid} 做入参校验。
 */
@Data
public class ChatRequest {

    /** 用户提问（必填） */
    @NotBlank(message = "message 不能为空")
    private String message;

    /**
     * 可选：自定义 System Prompt（人设）。
     * 传了就覆盖默认人设。
     */
    private String systemPrompt;

    /**
     * 可选：随机性 0.0 ~ 2.0。
     * 不传则使用默认值 0.7。
     */
    @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
    @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
    private Double temperature;

    /**
     * 可选：多轮会话 ID。
     * 同一个 conversationId 的多次请求共享历史上下文。
     * 多轮接口 /chat/multi 必须传。
     */
    private String conversationId;
}