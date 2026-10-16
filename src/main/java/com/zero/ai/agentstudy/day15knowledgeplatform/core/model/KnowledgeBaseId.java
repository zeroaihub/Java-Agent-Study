package com.zero.ai.agentstudy.day15knowledgeplatform.core.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.io.Serializable;
import java.util.UUID;

/**
 * 知识库标识（强类型值对象）。
 *
 * @param value 知识库唯一标识
 * @author ZeroAi
 */
public record KnowledgeBaseId(String value) implements Serializable {

    public KnowledgeBaseId {
        if (value == null || value.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "KnowledgeBaseId 不能为空");
        }
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