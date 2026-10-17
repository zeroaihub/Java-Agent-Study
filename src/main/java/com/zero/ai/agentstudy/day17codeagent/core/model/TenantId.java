package com.zero.ai.agentstudy.day17codeagent.core.model;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;

/** 租户 ID（强类型），多租户隔离的第一道约束。 */
public record TenantId(String value) {

    public TenantId {
        CodeException.requireText(value, "tenantId");
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
