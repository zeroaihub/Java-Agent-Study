package com.zero.ai.agentstudy.back.bot.session;

import com.zero.ai.agentstudy.back.bot.dto.TokenUsage;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天会话聚合根
 * 封装一个会话的全部状态: 系统提示 + 历史 + token统计
 *
 * @author ZeroAi
 */
@Data
public class ChatSession {

    /** 会话ID */
    private String sessionId;

    /** 系统提示词(可为空,用默认) */
    private String systemPrompt;

    /** 历史消息(不含system, system单独存) */
    private List<ChatMessage> messages = new ArrayList<>();

    /** token 累计统计 */
    private TokenUsage usage = TokenUsage.empty();

    /** 创建时间 */
    private long createdAt = System.currentTimeMillis();

    public ChatSession(String sessionId, String systemPrompt) {
        this.sessionId = sessionId;
        this.systemPrompt = systemPrompt;
    }

    /** 追加一条消息 */
    public void appendMessage(ChatMessage message) {
        this.messages.add(message);
    }
}
