package com.zero.ai.agentstudy.day07mcp.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

/**
 * CallToolResult —— tools/call 的返回结果模型。
 *
 * <p>教学要点：MCP 规范里，工具调用的结果不是一段裸字符串，而是一个结构体：</p>
 * <ul>
 *   <li>{@code content}：内容块数组，每个块形如 {@code {"type":"text","text":"..."}}，
 *       支持文本、图片等多种类型（Day07 只用 text）；</li>
 *   <li>{@code isError}：布尔位。注意区分两种「错误」：
 *       <ul>
 *         <li>协议级错误（方法不存在等）→ 走 JsonRpcResponse.error；</li>
 *         <li>工具级错误（业务失败，如城市不存在）→ 成功返回响应，但 isError=true，
 *             让模型知道「工具执行了但结果是失败」，从而决定重试或换策略。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>设计原因：把「工具业务失败」放进正常结果里（isError=true）而非抛协议错误，
 * 是 MCP 的刻意设计——让模型自己判断如何应对失败，而不是直接中断整条链路。</p>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CallToolResult {

    /** 内容块数组，每项如 {type:text, text:...} */
    private List<Map<String, Object>> content;

    /** 是否为「工具业务失败」 */
    private boolean isError;

    /**
     * 构造成功的文本结果。
     *
     * @param text 文本内容
     * @return 结果对象（isError=false）
     */
    public static CallToolResult ok(String text) {
        return new CallToolResult(
                List.of(Map.of("type", "text", "text", text)),
                false
        );
    }

    /**
     * 构造「工具业务失败」的文本结果。
     *
     * @param text 失败说明
     * @return 结果对象（isError=true）
     */
    public static CallToolResult fail(String text) {
        return new CallToolResult(
                List.of(Map.of("type", "text", "text", text)),
                true
        );
    }

    /**
     * 便捷方法：把所有文本内容块拼接成一个字符串，供上层直接展示。
     *
     * @return 拼接后的文本
     */
    public String asText() {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : content) {
            Object t = block.get("text");
            if (t != null) {
                sb.append(t);
            }
        }
        return sb.toString();
    }
}