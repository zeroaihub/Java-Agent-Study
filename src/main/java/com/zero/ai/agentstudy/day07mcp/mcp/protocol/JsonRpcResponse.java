package com.zero.ai.agentstudy.day07mcp.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * JsonRpcResponse —— JSON-RPC 2.0 「响应」报文模型。
 *
 * <p>教学要点：响应与请求通过相同的 {@code id} 一一对应。一个合法响应里，
 * {@code result} 和 {@code error} 二者**必居其一**：</p>
 * <ul>
 *   <li>成功：填充 {@code result}，{@code error} 为 null；</li>
 *   <li>失败：填充 {@code error}，{@code result} 为 null。</li>
 * </ul>
 *
 * <p>设计原因：用静态工厂 {@link #success} / {@link #error} 保证二者互斥，
 * 避免调用方误把两个字段同时塞进去，产生非法响应。</p>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {

    /** 协议版本，固定 "2.0" */
    private String jsonrpc = "2.0";

    /** 与请求相同的 id */
    private Object id;

    /** 成功结果（与 error 互斥） */
    private Object result;

    /** 错误对象（与 result 互斥） */
    private JsonRpcError error;

    /**
     * 构造成功响应。
     *
     * @param id     与请求相同的 id
     * @param result 结果对象
     * @return 成功响应
     */
    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse r = new JsonRpcResponse();
        r.setJsonrpc("2.0");
        r.setId(id);
        r.setResult(result);
        return r;
    }

    /**
     * 构造错误响应。
     *
     * @param id    与请求相同的 id
     * @param error 错误对象
     * @return 错误响应
     */
    public static JsonRpcResponse error(Object id, JsonRpcError error) {
        JsonRpcResponse r = new JsonRpcResponse();
        r.setJsonrpc("2.0");
        r.setId(id);
        r.setError(error);
        return r;
    }

    /** 便捷判断：是否为错误响应 */
    public boolean isError() {
        return error != null;
    }
}