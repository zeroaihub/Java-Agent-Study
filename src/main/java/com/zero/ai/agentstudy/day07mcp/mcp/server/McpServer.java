package com.zero.ai.agentstudy.day07mcp.mcp.server;

import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcError;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcRequest;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcResponse;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.McpMethods;
import com.zero.ai.agentstudy.day07mcp.mcp.registry.ToolRegistryDay07;
import com.zero.ai.agentstudy.day07mcp.mcp.tool.McpTool;
import com.zero.ai.agentstudy.day07mcp.util.McpTraceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * McpServer —— MCP 服务端核心（协议分发器）。
 *
 * <p>教学要点：Server 的唯一职责是「读懂 JSON-RPC 请求的 method，分发到对应处理逻辑，
 * 再把结果包装成 JSON-RPC 响应」。它是一个纯粹的「协议中枢」：</p>
 * <ul>
 *   <li>不认识任何具体工具（工具的事全交给 {@link ToolRegistryDay07}）；</li>
 *   <li>不关心传输方式（传输的事交给 Transport）。</li>
 * </ul>
 *
 * <p>支持的方法：</p>
 * <ul>
 *   <li>{@code initialize}：能力协商，返回 Server 的协议版本与能力声明；</li>
 *   <li>{@code notifications/initialized}：握手完成通知，无需响应；</li>
 *   <li>{@code tools/list}：返回所有工具定义（工具发现）；</li>
 *   <li>{@code tools/call}：按 name 找到工具并执行（工具调用）。</li>
 * </ul>
 *
 * <p>错误处理遵循 JSON-RPC：方法不存在→-32601；参数非法→-32602；
 * 工具抛异常→-32000（协议级错误）；而工具的业务失败则封装进正常结果的 isError=true。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class McpServer {

    /** 本 Server 声明的协议版本（示例值） */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** Server 名称与版本，握手时告知 Client */
    private static final String SERVER_NAME = "agentstudy-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";

    private final ToolRegistryDay07 toolRegistry;
    private final McpTraceLogger traceLogger;

    /**
     * 构造器注入依赖。
     *
     * @param toolRegistry 工具注册中心
     * @param traceLogger  链路日志器
     */
    public McpServer(ToolRegistryDay07 toolRegistry, McpTraceLogger traceLogger) {
        this.toolRegistry = toolRegistry;
        this.traceLogger = traceLogger;
    }

    /**
     * 处理一个 JSON-RPC 请求，返回响应。
     *
     * <p>这是 Server 的总入口，Transport 收到报文后调用它。</p>
     *
     * @param request JSON-RPC 请求
     * @return JSON-RPC 响应；若为通知则返回 null（不应答）
     */
    public JsonRpcResponse handle(JsonRpcRequest request) {
        String method = request.getMethod();
        Object id = request.getId();
        traceLogger.logReceive(method, id);

        // 通知类请求（如 initialized）：按规范不应答
        if (request.isNotification()) {
            log.info("[McpServer] 收到通知 method={}，无需响应", method);
            return null;
        }

        JsonRpcResponse response;
        try {
            response = switch (method == null ? "" : method) {
                case McpMethods.INITIALIZE -> handleInitialize(request);
                case McpMethods.TOOLS_LIST -> handleToolsList(request);
                case McpMethods.TOOLS_CALL -> handleToolsCall(request);
                default -> JsonRpcResponse.error(id,
                        JsonRpcError.of(JsonRpcError.METHOD_NOT_FOUND, "方法不存在: " + method));
            };
        } catch (Exception e) {
            // 兜底：任何未预期的异常都转成协议级内部错误，绝不让 Server 崩掉
            log.error("[McpServer] 处理请求异常 method={}", method, e);
            response = JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcError.INTERNAL_ERROR, "服务端内部错误: " + e.getMessage()));
        }

        traceLogger.logResponse(method, id, response != null && response.isError());
        return response;
    }

    /**
     * 处理 initialize：能力协商握手。
     *
     * @param request 请求
     * @return 含 Server 能力声明的响应
     */
    private JsonRpcResponse handleInitialize(JsonRpcRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        // capabilities：声明 Server 支持 tools 能力
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        result.put("capabilities", capabilities);
        // serverInfo：告知 Client 自己是谁
        result.put("serverInfo", Map.of(
                "name", SERVER_NAME,
                "version", SERVER_VERSION
        ));
        log.info("[McpServer] initialize 完成，协议版本={}", PROTOCOL_VERSION);
        return JsonRpcResponse.success(request.getId(), result);
    }

    /**
     * 处理 tools/list：返回所有工具定义。
     *
     * @param request 请求
     * @return 含 tools 数组的响应
     */
    private JsonRpcResponse handleToolsList(JsonRpcRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("tools", toolRegistry.listDefinitions());
        log.info("[McpServer] tools/list 返回 {} 个工具", toolRegistry.toolNames().size());
        return JsonRpcResponse.success(request.getId(), result);
    }

    /**
     * 处理 tools/call：定位工具并执行。
     *
     * <p>params 结构：{@code {"name":"get_weather","arguments":{"city":"北京"}}}</p>
     *
     * @param request 请求
     * @return 含 CallToolResult 的响应；工具不存在返回 -32602 错误
     */
    @SuppressWarnings("unchecked")
    private JsonRpcResponse handleToolsCall(JsonRpcRequest request) {
        Object id = request.getId();
        Map<String, Object> params = request.getParams();
        if (params == null || params.get("name") == null) {
            return JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcError.INVALID_PARAMS, "缺少参数 name"));
        }

        String toolName = String.valueOf(params.get("name"));
        McpTool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            // 工具不存在属于「参数非法」范畴
            return JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcError.INVALID_PARAMS, "工具不存在: " + toolName));
        }

        // 解析 arguments（可能为空）
        Object argObj = params.get("arguments");
        Map<String, Object> arguments = (argObj instanceof Map)
                ? (Map<String, Object>) argObj
                : new HashMap<>();

        // 执行工具并埋点计时
        traceLogger.logToolStart(toolName, arguments);
        long start = System.currentTimeMillis();
        CallToolResult toolResult;
        try {
            toolResult = tool.execute(arguments);
        } catch (Exception e) {
            // 工具内部抛异常 → 协议级 SERVER_ERROR
            long cost = System.currentTimeMillis() - start;
            traceLogger.logToolEnd(toolName, true, cost, "异常: " + e.getMessage());
            log.error("[McpServer] 工具执行异常 tool={}", toolName, e);
            return JsonRpcResponse.error(id,
                    JsonRpcError.of(JsonRpcError.SERVER_ERROR, "工具执行异常: " + e.getMessage()));
        }
        long cost = System.currentTimeMillis() - start;
        traceLogger.logToolEnd(toolName, toolResult.isError(), cost, toolResult.asText());

        // 无论工具业务成功/失败，都是一次「成功的协议调用」，结果放 result
        return JsonRpcResponse.success(id, toolResult);
    }

    /** 暴露协议版本，便于 Client 校验 */
    public String protocolVersion() {
        return PROTOCOL_VERSION;
    }
}