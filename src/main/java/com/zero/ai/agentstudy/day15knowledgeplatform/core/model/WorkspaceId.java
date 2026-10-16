package com.zero.ai.agentstudy.day15knowledgeplatform.core.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.io.Serializable;
import java.util.UUID;

/**
 * 工作空间标识（强类型值对象）。
 *
 * @param value 工作空间唯一标识
 * @author ZeroAi
 */
public record WorkspaceId(String value) implements Serializable {

    public WorkspaceId {
        if (value == null || value.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "WorkspaceId 不能为空");
        }
    }

    public static WorkspaceId of(String value) {
        return new WorkspaceId(value);
    }

    public static WorkspaceId newId() {
        return new WorkspaceId("ws-" + UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value;
    }
}