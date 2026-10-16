package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.port;

import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.ConversationMemory;
import com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model.MemoryMessage;

import java.util.Optional;

/**
 * 短期记忆存储端口（Port）。
 *
 * <p>负责会话短期记忆（{@link ConversationMemory}）的读写。这是一个<strong>领域端口</strong>，
 * 只定义「能做什么」，不关心「怎么存」——具体实现（内存 / Redis / 数据库）由基础设施层适配器提供，
 * 符合六边形架构「依赖倒置」原则：领域与应用层依赖此接口，而非任何具体存储技术。</p>
 *
 * <p>本教程提供内存实现，生产环境可替换为 Redis（带 TTL 过期），
 * 与 {@code wechat-session} 的会话生命周期对齐。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public interface ConversationMemoryStore {

    /**
     * 按记忆键加载会话记忆。
     *
     * @param memoryKey 记忆唯一键
     * @return 记忆（不存在时返回 {@link Optional#empty()}）
     */
    Optional<ConversationMemory> load(String memoryKey);

    /**
     * 向指定会话记忆追加一条消息（不存在则新建）。
     *
     * @param memoryKey 记忆唯一键
     * @param message   待追加的消息
     * @return 追加后的最新记忆
     */
    ConversationMemory append(String memoryKey, MemoryMessage message);

    /**
     * 覆盖保存整个会话记忆。
     *
     * @param memory 会话记忆
     */
    void save(ConversationMemory memory);

    /**
     * 清空指定会话记忆（如用户执行 /reset 时）。
     *
     * @param memoryKey 记忆唯一键
     */
    void clear(String memoryKey);
}