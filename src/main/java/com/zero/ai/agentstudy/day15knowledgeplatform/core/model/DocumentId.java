package com.zero.ai.agentstudy.day15knowledgeplatform.core.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.io.Serializable;
import java.util.UUID;

/**
 * 文档标识（强类型值对象）。
 *
 * @param value 文档唯一标识
 * @author ZeroAi
 */
public record DocumentId(String value) implements Serializable {

    public DocumentId {
        if (value == null || value.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "DocumentId 不能为空");
        }
    }

    public static DocumentId of(String value) {
        return new DocumentId(value);
    }

    public static DocumentId newId() {
        return new DocumentId("doc-" + UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value;
    }
}