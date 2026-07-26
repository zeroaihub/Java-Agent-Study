package com.zero.ai.agentstudy.back.bot.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求 DTO
 *
 * @author ZeroAi
 */
@Data
public class ChatRequest {

    /** 会话ID(多轮对话用,为空则新建) */
    private String sessionId;

    /** 用户消息(必填) */
    @NotBlank(message = "消息不能为空")
    private String message;

    /** 自定义系统提示词(可选,为空则用默认人设) */
    private String systemPrompt;

    /** 温度(可选,为空用默认0.7) */
    private Double temperature;
}
