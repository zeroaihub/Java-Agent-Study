package com.zero.ai.agentstudy.back.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Chat Completions 响应模型
 * 对应 POST /v1/chat/completions 的响应体
 *
 * 重点: usage 字段包含 token 统计(知识点7会详解)
 *
 * @author ZeroAi
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionResponse {

    /** 响应ID */
    private String id;

    /** 创建时间戳 */
    private Long created;

    /** 模型名 */
    private String model;

    /** 候选回复列表(通常 n=1 时只有一个) */
    private List<Choice> choices;

    /** Token 使用统计(影响成本!) */
    private Usage usage;

    /**
     * 候选回复
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        /** 索引 */
        private Integer index;
        /** 消息 */
        private ChatMessage message;
        /** 结束原因: stop / length / tool_calls / content_filter */
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /**
     * Token 使用统计
     * 这是计费的依据! 后面知识点7会详解
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        /** 提示词 token(输入) */
        @JsonProperty("prompt_tokens")
        private Integer promptTokens;
        /** 回复 token(输出) */
        @JsonProperty("completion_tokens")
        private Integer completionTokens;
        /** 总 token */
        @JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
