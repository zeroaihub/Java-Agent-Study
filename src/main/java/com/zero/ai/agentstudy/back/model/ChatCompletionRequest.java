package com.zero.ai.agentstudy.back.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Chat Completions 请求模型
 * 对应 POST /v1/chat/completions 的请求体
 *
 * @author ZeroAi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {

    /** 模型名, 如 deepseek-chat / gpt-4o / qwen-plus */
    private String model;

    /** 对话消息列表(注意: 是数组!) 后面知识点3会详解 */
    private List<ChatMessage> messages;

    /** 采样温度: 0~2, 越高越随机, 越低越确定 */
    private Double temperature;

    /** 核采样: 0~1, 替代 temperature 的另一种采样策略 */
    private Double topP;

    /** 生成最大 token 数 */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** 是否流式返回 */
    private Boolean stream;

    /** 输出格式控制(用于结构化输出) */
    @JsonProperty("response_format")
    private ResponseFormat responseFormat;

    /**
     * 响应格式控制
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponseFormat {
        /** 类型: text / json_object / json_schema */
        private String type;
        /** json_schema 的具体定义 */
        @JsonProperty("json_schema")
        private JsonSchemaSchema jsonSchema;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonSchemaSchema {
        private String name;
        private Object schema;
    }
}
