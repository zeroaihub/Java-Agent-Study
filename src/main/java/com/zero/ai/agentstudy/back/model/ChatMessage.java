package com.zero.ai.agentstudy.back.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息模型
 * 对应 OpenAI Chat Completions 协议中的 message 对象
 *
 * role 取值:
 *   - system:    系统提示, 设定 AI 的行为/人设
 *   - user:      用户输入
 *   - assistant: AI 的回复
 *   - tool:      工具调用结果(Agent 阶段会用到)
 *
 * @author ZeroAi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    /** 角色: system / user / assistant / tool */
    private String role;

    /** 消息内容 */
    private String content;

    /**
     * 思考过程内容(reasoning/思考型模型专用)。
     * 如 Qwen-reasoning / DeepSeek-R1 等模型, 在正式回答前会先输出思考过程,
     * 此时 content 为 null, 思考文本放在 reasoning_content 字段。
     */
    @JsonProperty("reasoning_content")
    private String reasoningContent;

    /** 工具调用ID(tool 角色使用) */
    private String toolCallId;
}
