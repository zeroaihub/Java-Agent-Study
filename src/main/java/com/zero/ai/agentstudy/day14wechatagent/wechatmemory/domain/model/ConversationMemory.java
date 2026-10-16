package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 会话短期记忆（聚合根）。
 *
 * <p>{@code ConversationMemory} 承载「一次会话内的多轮对话历史」，是短期记忆的载体。
 * 它以 {@code memoryKey}（通常由 租户 + 渠道 + 会话 三要素派生）为唯一标识，
 * 内部维护一个按时间排序的 {@link MemoryMessage} 序列。</p>
 *
 * <p>核心能力——<strong>窗口裁剪（sliding window）</strong>：LLM 的上下文长度有限，
 * 不可能把无限增长的历史全部喂给模型。本聚合提供：</p>
 * <ul>
 *     <li>{@link #append(MemoryMessage)} —— 追加一条消息。</li>
 *     <li>{@link #recent(int)} —— 取最近 N 条（按条数裁剪）。</li>
 *     <li>{@link #recentByTokenBudget(int)} —— 按 token 预算从后往前裁剪（按预算裁剪）。</li>
 * </ul>
 *
 * <p>该聚合本身<strong>不做持久化</strong>，读写由
 * {@code ConversationMemoryStore} 端口负责，符合六边形架构「领域不依赖基础设施」原则。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public class ConversationMemory {

    /** 记忆唯一键（租户+渠道+会话派生）。 */
    private final String memoryKey;

    /** 按时间顺序的消息序列。 */
    private final List<MemoryMessage> messages;

    /**
     * 构造一个会话记忆。
     *
     * @param memoryKey 记忆唯一键
     * @param messages  初始消息序列（可为空）
     */
    public ConversationMemory(String memoryKey, List<MemoryMessage> messages) {
        this.memoryKey = Objects.requireNonNull(memoryKey, "memoryKey 不能为空");
        this.messages = new ArrayList<>(messages == null ? List.of() : messages);
    }

    /**
     * 创建一个空的会话记忆。
     *
     * @param memoryKey 记忆唯一键
     * @return 空记忆
     */
    public static ConversationMemory empty(String memoryKey) {
        return new ConversationMemory(memoryKey, new ArrayList<>());
    }

    /**
     * 派生记忆唯一键。与会话模块保持一致的三要素结构，便于跨模块对齐。
     *
     * @param tenantId       租户
     * @param channelType    渠道
     * @param conversationId 会话
     * @return 记忆键，形如 {@code tenant:channel:conversation}
     */
    public static String memoryKey(String tenantId, String channelType, String conversationId) {
        return tenantId + ":" + channelType + ":" + conversationId;
    }

    /**
     * 追加一条记忆消息（有副作用，修改聚合内部状态）。
     *
     * @param message 待追加的消息
     */
    public void append(MemoryMessage message) {
        this.messages.add(Objects.requireNonNull(message, "message 不能为空"));
    }

    /**
     * 取最近 N 条消息（按条数裁剪窗口）。
     *
     * @param maxTurns 最多保留的消息条数；<=0 时返回空列表
     * @return 只读的最近消息列表（时间升序）
     */
    public List<MemoryMessage> recent(int maxTurns) {
        if (maxTurns <= 0 || messages.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, messages.size() - maxTurns);
        return Collections.unmodifiableList(new ArrayList<>(messages.subList(from, messages.size())));
    }

    /**
     * 按 token 预算从最新往最旧裁剪（按预算裁剪窗口）。
     *
     * <p>从最后一条消息开始累加估算 token，一旦超过预算即停止，
     * 保证喂给模型的历史不超上下文上限。返回结果仍保持时间升序。</p>
     *
    * @param tokenBudget token 预算；<=0 时返回空列表
     * @return 只读的裁剪后消息列表（时间升序）
     */
    public List<MemoryMessage> recentByTokenBudget(int tokenBudget) {
        if (tokenBudget <= 0 || messages.isEmpty()) {
            return List.of();
        }
        List<MemoryMessage> picked = new ArrayList<>();
        int used = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MemoryMessage m = messages.get(i);
            int cost = m.estimatedTokens();
            if (used + cost > tokenBudget && !picked.isEmpty()) {
                break;
            }
            picked.add(m);
            used += cost;
        }
        Collections.reverse(picked);
        return Collections.unmodifiableList(picked);
    }

    /**
     * @return 全部消息的只读视图（时间升序）
     */
    public List<MemoryMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    /** @return 记忆唯一键 */
    public String memoryKey() {
        return memoryKey;
    }

    /** @return 当前消息条数 */
    public int size() {
        return messages.size();
    }

    /** @return 是否为空记忆 */
    public boolean isEmpty() {
        return messages.isEmpty();
    }
}