package com.zero.ai.agentstudy.day17codeagent.core.model;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;

/** 用户 ID（强类型）。 */
public record UserId(String value) {

    public UserId {
        CodeException.requireText(value, "userId");
    }

    public static UserId of(String value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
