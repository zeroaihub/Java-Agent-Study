package com.zero.ai.agentstudy.day16knowledgeagentv2.core.model;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.io.Serializable;

/**
 * 用户标识（强类型值对象）。权限感知检索（ACL）以此为主体判定可见性。
 *
 * @param value 用户唯一标识
 * @author ZeroAi
 */
public record UserId(String value) implements Serializable {

    public UserId {
     AgentException.requireText(value, "UserId");
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}