package com.zero.ai.agentstudy.back.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 流式响应的单个数据块(SSE chunk)
 *
 * 流式输出时, 服务器不是一次性返回完整 JSON,
 * 而是分成多个 chunk 通过 Server-Sent Events 推送,
 * 每个 chunk 包含增量内容(delta), 最后一个 chunk 的 finish_reason 标记结束。
 *
 * 示例流:
 *   data: {"choices":[{"delta":{"content":"你"}}]}
 *   data: {"choices":[{"delta":{"content":"好"}}]}
 *   data: {"choices":[{"delta":{"content":"!"},"finish_reason":"stop"}]}
 *   data: [DONE]
 *
 * @author ZeroAi
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionChunk {

    private String id;
    private Long created;
    private String model;
    private List<ChunkChoice> choices;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChunkChoice {
        private Integer index;
        /** 增量内容(本次新增的内容, 而非完整内容) */
        private ChatMessage delta;
        @JsonProperty("finish_reason")
        private String finishReason;
    }
}
