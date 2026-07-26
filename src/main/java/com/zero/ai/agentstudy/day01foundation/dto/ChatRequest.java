package com.zero.ai.agentstudy.day01foundation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求参数
 */
@Data
public class ChatRequest {

    /** 用户输入的问题（必填） */
    @NotBlank(message = "message 不能为空")
    private String message;

    /**
     * System Prompt：设定 AI 的角色/行为。可选。
     * 例如："你是一位资深Java架构师，回答要专业、简洁。"
     */
    private String systemPrompt;

    /**
     * 温度：0.0 ~ 2.0，越大越发散，越小越严谨。可选。
     * 不传时使用配置文件默认值。
     */
    private Double temperature;
}