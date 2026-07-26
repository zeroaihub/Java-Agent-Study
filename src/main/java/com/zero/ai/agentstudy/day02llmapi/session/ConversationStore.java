package com.zero.ai.agentstudy.day02llmapi.session;

import com.zero.ai.agentstudy.day02llmapi.config.Day02ChatProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易多轮会话存储（内存版）。
 * <p>
 * 按 {@code conversationId} 维护历史消息列表，实现第三章讲的
 * user / assistant 历史记忆。为控制上下文长度，超出
 * {@link Day02ChatProperties#getMaxHistorySize()} 的旧消息会被裁剪。
 * <p>
 * <b>注意（第五/六章红线）</b>：这是 demo 级实现，进程重启即丢、无法水平扩展、
 * 会随会话增长占用内存。生产应替换为 Redis / 数据库并设置过期与淘汰。
 */
@Component
public class ConversationStore {

    /** conversationId -> 历史消息 */
    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    private final Day02ChatProperties properties;

    public ConversationStore(Day02ChatProperties properties) {
        this.properties = properties;
    }

    /**
     * 获取某会话的历史消息（只读副本）。
     */
    public List<Message> getHistory(String conversationId) {
        return new ArrayList<>(store.getOrDefault(conversationId, new ArrayList<>()));
    }

    /**
     * 追加一轮对话（用户提问 + AI 回答）并做窗口裁剪。
     *
     * @param conversationId 会话 ID
     * @param userText       用户提问
     * @param assistantText  AI 回答
     */
    public void append(String conversationId, String userText, String assistantText) {
        List<Message> history = store.computeIfAbsent(conversationId, k -> new ArrayList<>());
        synchronized (history) {
            history.add(new UserMessage(userText));
            history.add(new AssistantMessage(assistantText));
            trim(history);
        }
    }

    /**
     * 裁剪历史，只保留最近 maxHistorySize 条消息。
     */
    private void trim(List<Message> history) {
        int max = properties.getMaxHistorySize();
        while (history.size() > max) {
            history.remove(0);
        }
    }

    /**
     * 清空某会话（可供 reset 接口调用）。
     */
    public void clear(String conversationId) {
        store.remove(conversationId);
    }
}