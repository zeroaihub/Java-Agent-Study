package com.zero.ai.agentstudy.day07mcp.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Map;

/**
 * JsonRpcRequest —— JSON-RPC 2.0 「请求」报文模型。
 *
 * <p>教学要点：MCP 的所有交互都建立在 JSON-RPC 2.0 之上。JSON-RPC 是一个极简的
 * 远程调用规范，一个「请求」由四部分构成：</p>
 * <ul>
 *   <li>{@code jsonrpc}：协议版本，固定为 "2.0"；</li>
 *   <li>{@code id}：请求标识。有 id 表示这是一个「期待响应」的请求；
 *       若为 null（不序列化）则表示这是一条「通知(Notification)」，不需要响应；</li>
 *   <li>{@code method}：要调用的方法名，MCP 里如 "initialize" / "tools/list" / "tools/call"；</li>
 *   <li>{@code params}：方法参数，用 Map 承载任意结构（也可换成强类型对象）。</li>
 * </ul>
 *
 * <p>设计原因：用 {@code @JsonInclude(NON_NULL)} 让 id 为空时不出现在报文里，
 * 从而天然表达「通知」语义，符合 JSON-RPC 规范。</p>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcRequest {

    /** 协议版本，固定 "2.0" */
    private String jsonrpc = "2.0";

    /** 请求 id：非空=需要响应；空=通知 */
    private Object id;

    /** 方法名，如 tools/list、tools/call */
    private String method;

    /** 方法参数 */
    private Map<String, Object> params;

    /**
     * 构造一个「需要响应」的请求。
     *
     * @param id     请求标识
     * @param method 方法名
     * @param params 参数
     * @return 请求对象
     */
    public static JsonRpcRequest of(Object id, String method, Map<String, Object> params) {
        return new JsonRpcRequest("2.0", id, method, params);
    }

    /**
     * 构造一条「通知」（无 id，不期待响应）。
     *
     * @param method 方法名
     * @param params 参数
     * @return 通知请求对象
     */
    public static JsonRpcRequest notification(String method, Map<String, Object> params) {
        return new JsonRpcRequest("2.0", null, method, params);
    }

    /** 判断是否为通知（无 id） */
    public boolean isNotification() {
        return id == null;
    }
}