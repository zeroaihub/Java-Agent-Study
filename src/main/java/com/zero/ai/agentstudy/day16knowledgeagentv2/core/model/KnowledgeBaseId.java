package com.zero.ai.agentstudy.day16knowledgeagentv2.core.model;

import com.zero.ai.agentstudy.day16knowledgeagentv2.core.common.AgentException;

import java.io.Serializable;
import java.util.UUID;

/**
 * 知识库标识（强类型值对象）。Knowledge Routing 的路由目标即为该 ID。
 *
 * @param value 知识库唯一标识
 * @author ZeroAi
 */
public record KnowledgeBaseId(String value) implements Serializable {

    public KnowledgeBaseId {
        AgentException.requireText(value, "KnowledgeBaseId");
    }

    public static KnowledgeBaseId of(String value) {
        return new KnowledgeBaseId(value);
    }

    public static KnowledgeBaseId newId() {
        return new KnowledgeBaseId("kb-" + UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value;
    }
}