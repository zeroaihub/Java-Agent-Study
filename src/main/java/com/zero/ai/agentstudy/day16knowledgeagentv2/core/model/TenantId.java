package com.zero.ai.agentstudy.day16knowledgeagentv2.core.model;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.io.Serializable;

/**
 * 租户标识（强类型值对象）——多租户隔离的第一道防线。
 *
 * <p>不用裸 String 表示 ID，编译期即可阻止「把 UserId 传给需要 TenantId 的方法」这类错误。</p>
 *
 * @param value 租户唯一标识
 * @author ZeroAi
 */
public record TenantId(String value) implements Serializable {

    public TenantId {
        AgentException.requireText(value, "TenantId");
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}