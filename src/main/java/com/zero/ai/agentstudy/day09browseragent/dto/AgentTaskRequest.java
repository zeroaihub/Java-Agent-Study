package com.zero.ai.agentstudy.day09browseragent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AgentTaskRequest —— 提交给 Browser Agent 的自然语言任务请求。
 *
 * @author AI架构师
 */
@Data
public class AgentTaskRequest {

    /** 自然语言任务指令，如「打开 https://example.com 并总结页面内容」 */
    @NotBlank(message = "任务指令不能为空")
    private String instruction;
}