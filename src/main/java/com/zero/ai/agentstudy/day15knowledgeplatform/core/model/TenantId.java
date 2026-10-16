package com.zero.ai.agentstudy.day15knowledgeplatform.core.model;

import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeErrorCode;
import com.zero.ai.agentstudy.day15knowledgeplatform.core.common.KnowledgeException;

import java.io.Serializable;

/**
 * 租户标识（强类型值对象）。
 *
 * <p>DDD 实践：不要用裸 String 表示 ID。强类型 ID 能在编译期阻止
 * 「把 DocumentId 传给需要 TenantId 的方法」这类错误，是多租户安全的第一道防线。</p>
 *
 * @param value 租户唯一标识
 * @author ZeroAi
 */
public record TenantId(String value) implements Serializable {

    public TenantId {
        if (value == null || value.isBlank()) {
            throw new KnowledgeException(KnowledgeErrorCode.TENANT_REQUIRED, "TenantId 不能为空");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}