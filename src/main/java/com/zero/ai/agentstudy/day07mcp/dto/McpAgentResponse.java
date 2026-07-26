package com.zero.ai.agentstudy.day07mcp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * McpAgentResponse —— Agent 处理结果的统一返回体。
 *
 * <p>教学要点：给前端/调用方一个稳定、语义化的返回结构，屏蔽内部协议细节。</p>
 * <ul>
 *   <li>{@code success}：本次请求整体是否成功；</li>
 *   <li>{@code toolUsed}：Agent 最终选用的工具名（没用工具则为 null）；</li>
 *   <li>{@code answer}：给用户看的最终回答；</li>
 *   <li>{@code message}：附加说明（错误原因等）。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpAgentResponse {

    /** 整体是否成功 */
    private boolean success;

    /** 本次用到的工具名 */
    private String toolUsed;

    /** 给用户的最终回答 */
    private String answer;

    /** 附加说明 */
    private String message;

    /**
     * 构造成功结果。
     *
     * @param toolUsed 使用的工具
     * @param answer   回答
     * @return 响应
     */
    public static McpAgentResponse ok(String toolUsed, String answer) {
        return McpAgentResponse.builder()
                .success(true)
                .toolUsed(toolUsed)
                .answer(answer)
                .message("ok")
                .build();
    }

    /**
     * 构造失败结果。
     *
     * @param message 失败原因
     * @return 响应
     */
    public static McpAgentResponse fail(String message) {
        return McpAgentResponse.builder()
                .success(false)
                .message(message)
                .build();
    }
}