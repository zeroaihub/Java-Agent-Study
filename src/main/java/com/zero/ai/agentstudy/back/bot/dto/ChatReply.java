package com.zero.ai.agentstudy.back.bot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天回复 DTO
 *
 * @author ZeroAi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReply {

    /** 会话ID */
    private String sessionId;

    /** AI 回复内容 */
    private String answer;

    /** 本次调用的 token 统计 */
    private TokenUsage usage;
}
