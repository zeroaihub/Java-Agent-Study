package com.zero.ai.agentstudy.day07mcp.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * JsonRpcError —— JSON-RPC 2.0 「错误对象」。
 *
 * <p>教学要点：当一次请求处理失败时，响应里不放 {@code result}，而是放一个 {@code error}。
 * 错误对象由三部分构成：</p>
 * <ul>
 *   <li>{@code code}：整数错误码（标准区间见下方常量）；</li>
 *   <li>{@code message}：简短的人类可读错误描述；</li>
 *   <li>{@code data}：可选的附加信息（如异常堆栈摘要、非法参数名）。</li>
 * </ul>
 *
 * <p>JSON-RPC 预定义的标准错误码：</p>
 * <ul>
 *   <li>-32700 Parse error：报文无法解析；</li>
 *   <li>-32600 Invalid Request：不是合法的请求对象；</li>
 *   <li>-32601 Method not found：方法不存在；</li>
 *   <li>-32602 Invalid params：参数非法；</li>
 *   <li>-32603 Internal error：内部错误；</li>
 *   <li>-32000 ~ -32099：服务端自定义错误（本项目用 -32000 表示工具执行异常）。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcError {

    /** 报文解析错误 */
    public static final int PARSE_ERROR = -32700;
    /** 非法请求 */
    public static final int INVALID_REQUEST = -32600;
    /** 方法不存在 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 参数非法 */
    public static final int INVALID_PARAMS = -32602;
    /** 内部错误 */
    public static final int INTERNAL_ERROR = -32603;
    /** 服务端自定义：工具执行异常 */
    public static final int SERVER_ERROR = -32000;

    /** 错误码 */
    private int code;

    /** 错误描述 */
    private String message;

    /** 附加数据（可选） */
    private Object data;

    /**
     * 快捷构造（无附加数据）。
     *
     * @param code    错误码
     * @param message 错误描述
     * @return 错误对象
     */
    public static JsonRpcError of(int code, String message) {
        return new JsonRpcError(code, message, null);
    }

    /**
     * 快捷构造（含附加数据）。
     *
     * @param code    错误码
     * @param message 错误描述
     * @param data    附加数据
     * @return 错误对象
     */
    public static JsonRpcError of(int code, String message, Object data) {
        return new JsonRpcError(code, message, data);
    }
}