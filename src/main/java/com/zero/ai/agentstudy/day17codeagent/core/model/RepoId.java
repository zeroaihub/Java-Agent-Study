package com.zero.ai.agentstudy.day17codeagent.core.model;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;

/** 代码仓库 ID（强类型）。 */
public record RepoId(String value) {

    public RepoId {
        CodeException.requireText(value, "repoId");
    }

    public static RepoId of(String value) {
        return new RepoId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
