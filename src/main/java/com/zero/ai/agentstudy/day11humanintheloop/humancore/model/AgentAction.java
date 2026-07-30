package com.zero.ai.agentstudy.day11humanintheloop.humancore.model;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 动作（值对象 / Value Object）。
 *
 * <p>这是 HITL 里最核心的一个数据结构：Agent 想"做的每一件事"都会先被封装成一个
 * {@code AgentAction}，然后交给风险策略评估、交给审批网关拦截。它是"人机协同"里
 * 人和机器共同讨论的那个"议题"。</p>
 *
 * <p>使用 {@code record}：动作一旦创建就不可变（immutable），这在并发、审计、
 * 重放（replay）场景下极其重要——你永远不希望一个已经被人工审批过的动作，
 * 在执行前又被谁悄悄改了字段。</p>
 *
 * @param taskId      所属任务 ID（用于把动作归属到某次 Agent 任务）
 * @param type        动作类型，例如 {@code DB_DELETE}、{@code SEND_EMAIL}、{@code CALL_API}
 * @param description 人类可读的动作描述，会展示给审批人看
 * @param params      动作参数（只读快照）
 * @param amount      涉及金额（可为 null；用于"金额越大风险越高"这类策略）
 */
public record AgentAction(
        String taskId,
        String type,
        String description,
        Map<String, Object> params,
        BigDecimal amount
) {

    /**
     * 紧凑构造器：做非空校验 + 防御性拷贝，保证真正的不可变。
     */
    public AgentAction {
        Objects.requireNonNull(taskId, "taskId 不能为空");
        Objects.requireNonNull(type, "type 不能为空");
        Objects.requireNonNull(description, "description 不能为空");
        // 防御性拷贝：外部即使持有原始 map 引用，也无法篡改本对象
        params = (params == null) ? Map.of() : Map.copyOf(params);
    }

    /**
     * 便捷工厂：无金额、无参数的简单动作。
     */
    public static AgentAction of(String taskId, String type, String description) {
        return new AgentAction(taskId, type, description, Map.of(), null);
    }

    /**
     * 便捷工厂：带参数的动作。
     */
    public static AgentAction of(String taskId, String type, String description, Map<String, Object> params) {
        return new AgentAction(taskId, type, description, params, null);
    }

    /**
     * 是否涉及金额（金额 > 0）。
     */
    public boolean hasAmount() {
        return amount != null && amount.signum() > 0;
    }

    /**
     * 安全读取某个参数，不存在返回默认值。
     */
    public Object paramOrDefault(String key, Object defaultValue) {
        return params.getOrDefault(key, defaultValue);
    }
}