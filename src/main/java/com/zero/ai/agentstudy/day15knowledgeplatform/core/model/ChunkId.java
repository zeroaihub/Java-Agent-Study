package com.zero.ai.agentstudy.day15knowledgeplatform.core.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.io.Serializable;
import java.util.UUID;

/**
 * 切片标识（强类型值对象）。
 *
 * @param value 切片唯一标识
 * @author ZeroAi
 */
public record ChunkId(String value) implements Serializable {

    public ChunkId {
        if (value == null || value.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.INVALID_ARGUMENT, "ChunkId 不能为空");
        }
    }

    public static ChunkId of(String value) {
        return new ChunkId(value);
    }

    public static ChunkId newId() {
        return new ChunkId("ck-" + UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value;
    }
}