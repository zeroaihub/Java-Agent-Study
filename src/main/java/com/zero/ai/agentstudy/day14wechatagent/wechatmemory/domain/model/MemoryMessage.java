package com.zero.ai.agentstudy.day14wechatagent.wechatmemory.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 记忆消息（短期记忆的最小单元）。
 *
 * <p>一条 {@code MemoryMessage} 记录了「谁（{@link MessageRole}）在何时（{@code createdAt}）
 * 说了什么（{@code content}）」，是多轮对话记忆的原子条目。
 * 它是一个<strong>不可变值对象</strong>：一旦生成不再修改，符合领域驱动设计中值对象的定义，
 * 也便于在并发环境下安全共享。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *     <li>使用 record 保证不可变与结构相等语义。</li>
 *     <li>提供 {@link #user(String)} / {@link #assistant(String)} / {@link #system(String)}
 *         三个语义化工厂方法，避免调用方手写角色枚举。</li>
 *     <li>{@code createdAt} 在工厂方法中统一取 {@link Instant#now()}，保证时序可排序。</li>
 * </ul>
 *
 * @param role      角色（system/user/assistant）
 * @param content   消息文本内容
 * @param createdAt 生成时间（UTC 时间戳）
 * @author ZeroHub AI Agent Platform
 */
public record MemoryMessage(MessageRole role, String content, Instant createdAt) {

    /**
     * 紧凑构造器：做非空与文本规范化校验。
     */
    public MemoryMessage {
        Objects.requireNonNull(role, "role 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        content = content.strip();
    }

    /**
     * 构造一条用户消息。
     *
     * @param content 用户文本
     * @return 记忆消息
     */
    public static MemoryMessage user(String content) {
        return new MemoryMessage(MessageRole.USER, content, Instant.now());
    }

    /**
     * 构造一条 Agent 回复消息。
     *
     * @param content 回复文本
     * @return 记忆消息
     */
    public static MemoryMessage assistant(String content) {
        return new MemoryMessage(MessageRole.ASSISTANT, content, Instant.now());
    }

    /**
     * 构造一条系统设定消息。
     *
     * @param content 系统设定文本
     * @return 记忆消息
     */
    public static MemoryMessage system(String content) {
        return new MemoryMessage(MessageRole.SYSTEM, content, Instant.now());
    }

    /**
     * 粗略估算该消息的 token 数（按「1 token ≈ 4 字符」的经验值），用于窗口裁剪时的预算控制。
     *
     * @return 估算 token 数（至少为 1）
     */
    public int estimatedTokens() {
        return Math.max(1, content.length() / 4);
    }
}