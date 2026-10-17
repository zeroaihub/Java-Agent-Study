package com.zero.ai.agentstudy.day17codeagent.core.model;

import com.zero.ai.agentstudy.day17codeagent.core.common.CodeException;

/** 任务 ID（强类型，避免与 String 混用导致「传错参数也编译不过」）。 */
public record TaskId(String value) {

    public TaskId {
        CodeException.requireText(value, "taskId");
    }

    public static TaskId of(String value) {
        return new TaskId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
