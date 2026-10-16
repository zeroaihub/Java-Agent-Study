package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.application;

import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.ConversationMemory;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.MemoryMessage;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port.ConversationMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 短期记忆应用服务（多轮对话记忆编排）。
 *
 * <p>面向上层（路由 / 聊天处理器）提供「记录用户消息、记录 Agent 回复、取最近对话窗口、重置记忆」
 * 四类能力，屏蔽底层存储细节。它是六边形架构中的<strong>应用层</strong>：
 * 只依赖 {@link ConversationMemoryStore} 端口，不关心内存还是 Redis。</p>
 *
 * <p>典型用法（一次问答的记忆闭环）：</p>
 * <pre>{@code
 * String key = memoryService.keyOf(tenant, channel, conv);
 * memoryService.recordUser(key, userText);        // 1. 记录用户输入
 * List<MemoryMessage> history = memoryService.recentWindow(key, 20); // 2. 取历史喂给模型
 * // ... 调用 LLM 得到 reply ...
 * memoryService.recordAssistant(key, reply);       // 3. 记录模型回复
 * }</pre>
 *
 * @author ZeroHub AI Agent Platform
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 默认最近窗口条数（防止历史无限增长撑爆上下文）。 */
    private static final int DEFAULT_WINDOW = 20;

    private final ConversationMemoryStore memoryStore;

    /**
     * 构造函数注入短期记忆存储端口。
     *
     * @param memoryStore 短期记忆存储端口
     */
    public MemoryService(ConversationMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * 派生记忆键（租户+渠道+会话）。
     *
     * @param tenantId       租户
     * @param channelType    渠道
     * @param conversationId 会话
     * @return 记忆键
     */
    public String keyOf(String tenantId, String channelType, String conversationId) {
        return ConversationMemory.memoryKey(tenantId, channelType, conversationId);
    }

    /**
     * 记录一条用户消息。
     *
     * @param memoryKey 记忆键
     * @param content   用户文本
     */
    public void recordUser(String memoryKey, String content) {
        memoryStore.append(memoryKey, MemoryMessage.user(content));
        log.debug("记录用户消息: key={}", memoryKey);
    }

    /**
     * 记录一条 Agent 回复。
     *
     * @param memoryKey 记忆键
     * @param content   回复文本
     */
    public void recordAssistant(String memoryKey, String content) {
        memoryStore.append(memoryKey, MemoryMessage.assistant(content));
        log.debug("记录模型回复: key={}", memoryKey);
    }

    /**
     * 取最近的对话窗口（按条数裁剪），用于拼装提示词。
     *
     * @param memoryKey 记忆键
     * @param maxTurns  最多条数；<=0 时使用默认窗口
     * @return 最近消息列表（时间升序）
     */
    public List<MemoryMessage> recentWindow(String memoryKey, int maxTurns) {
        int window = maxTurns > 0 ? maxTurns : DEFAULT_WINDOW;
        return memoryStore.load(memoryKey)
                .map(memory -> memory.recent(window))
                .orElseGet(List::of);
    }

    /**
     * 取满足 token 预算的对话窗口（按预算裁剪），更贴合真实上下文长度限制。
     *
     * @param memoryKey   记忆键
     * @param tokenBudget token 预算
     * @return 裁剪后的消息列表（时间升序）
     */
    public List<MemoryMessage> windowByTokenBudget(String memoryKey, int tokenBudget) {
        return memoryStore.load(memoryKey)
                .map(memory -> memory.recentByTokenBudget(tokenBudget))
                .orElseGet(List::of);
    }

    /**
     * 重置（清空）指定会话的短期记忆。与 {@code /reset} 命令联动。
     *
     * @param memoryKey 记忆键
     */
    public void reset(String memoryKey) {
        memoryStore.clear(memoryKey);
        log.info("重置短期记忆: key={}", memoryKey);
    }
}