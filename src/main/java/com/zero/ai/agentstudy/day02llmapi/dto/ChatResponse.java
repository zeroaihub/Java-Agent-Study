package com.zero.ai.agentstudy.day02llmapi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Day02 聊天响应 DTO。
 * <p>
 * 封装模型回答、Token 用量（对应 Chat Completion 的 usage）、会话与耗时信息，
 * 便于前端展示与后端做成本 / 性能核算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** 模型回答内容 */
    private String content;

    /** 使用的模型名 */
    private String model;

    /** 提示词消耗的 Token 数 */
    private Integer promptTokens;

    /** 生成内容消耗的 Token 数 */
    private Integer completionTokens;

    /** 总 Token 数 */
    private Integer totalTokens;

    /** 会话 ID（多轮时回显） */
    private String conversationId;

    /** 本次调用耗时（毫秒） */
    private Long costMs;

    /** 是否为降级兜底结果 */
    private boolean fallback;

    /**
     * 构造一个降级兜底响应。
     *
     * @param message 兜底文案
     * @return 标记为 fallback 的响应
     */
    public static ChatResponse fallback(String message) {
        return ChatResponse.builder()
                .content(message)
                .fallback(true)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .build();
    }
}