package com.zero.ai.agentstudy.day16knowledgeagentv2.core.model;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.io.Serializable;

/**
 * 工作空间标识（强类型值对象）。一个租户下可拥有多个 Workspace（如研发/财务/客服）。
 *
 * @param value 工作空间唯一标识
 * @author ZeroAi
 */
public record WorkspaceId(String value) implements Serializable {

    public WorkspaceId {
        AgentException.requireText(value, "WorkspaceId");
    }

    public static WorkspaceId of(String value) {
        return new WorkspaceId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}