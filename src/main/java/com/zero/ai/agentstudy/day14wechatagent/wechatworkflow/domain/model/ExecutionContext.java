package com.zero.ai.agentstudy.day14wechatagent.wechatworkflow.domain.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流执行上下文（「黑板」模式的共享数据空间）。
 *
 * <p>多步骤任务中，前一步的产出往往是后一步的输入。{@code ExecutionContext} 就是
 * 贯穿整条工作流的<strong>共享黑板（blackboard）</strong>：每个步骤把结果写入，
 * 后续步骤按 key 读取。例如「生成会议纪要」步骤写入 {@code summary}，
 * 「上传文件」步骤读取 {@code summary} 作为文件内容。</p>
 *
 * <p>它同时携带任务的不可变元信息（租户、渠道、会话、发起人），
 * 便于步骤执行器做多租户隔离与溯源。内部用 {@link ConcurrentHashMap} 保证并行步骤写入安全。</p>
 *
 * @author ZeroHub AI Agent Platform
 */
public class ExecutionContext {

    /** 租户标识（多租户隔离）。 */
    private final String tenantId;

    /** 渠道类型（如 WECHAT）。 */
    private final String channelType;

    /** 会话标识。 */
    private final String conversationId;

    /** 任务发起人。 */
    private final String userId;

    /** 步骤间共享的键值黑板。 */
    private final Map<String, Object> blackboard = new ConcurrentHashMap<>();

    /**
     * 构造执行上下文。
     *
     * @param tenantId       租户
     * @param channelType    渠道
     * @param conversationId 会话
     * @param userId         发起人
     */
    public ExecutionContext(String tenantId, String channelType, String conversationId, String userId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId 不能为空");
        this.channelType = Objects.requireNonNull(channelType, "channelType 不能为空");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId 不能为空");
        this.userId = Objects.requireNonNull(userId, "userId 不能为空");
    }

    /**
     * 写入一个共享变量。
     *
     * @param key   键
     * @param value 值
     */
    public void put(String key, Object value) {
        if (key != null && value != null) {
            blackboard.put(key, value);
        }
    }

    /**
     * 读取一个共享变量。
     *
     * @param key 键
     * @return 值的 Optional 包装
     */
    public Optional<Object> get(String key) {
        return Optional.ofNullable(blackboard.get(key));
    }

    /**
     * 按类型读取一个共享变量。
     *
     * @param key  键
     * @param type 期望类型
     * @param <T>  类型参数
     * @return 值的 Optional；类型不匹配时返回空
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        Object value = blackboard.get(key);
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    /**
     * 读取字符串变量，缺失时返回默认值。
     *
     * @param key          键
     * @param defaultValue 默认值
     * @return 字符串值或默认值
     */
    public String getString(String key, String defaultValue) {
        Object value = blackboard.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /** @return 是否包含指定键。 */
    public boolean contains(String key) {
        return blackboard.containsKey(key);
    }

    /** @return 租户标识 */
    public String tenantId() { return tenantId; }

    /** @return 渠道类型 */
    public String channelType() { return channelType; }

    /** @return 会话标识 */
    public String conversationId() { return conversationId; }

    /** @return 任务发起人 */
    public String userId() { return userId; }
}