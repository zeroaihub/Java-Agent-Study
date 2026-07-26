package com.zero.ai.agentstudy.day07mcp.mcp.client;

import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.entity.ToolDefinition;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcRequest;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcResponse;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.McpMethods;
import com.zero.ai.agentstudy.day07mcp.mcp.transport.Transport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * McpClient —— MCP 客户端（面向业务的高层封装）。
 *
 * <p>教学要点：Client 把「拼 JSON-RPC 请求 / 解析响应 / 维护请求 id」这些协议细节
 * 全部封装起来，对上层（Agent）只暴露三个语义化方法：</p>
 * <ul>
 *   <li>{@link #initialize()}：与 Server 握手协商能力；</li>
 *   <li>{@link #listTools()}：发现 Server 有哪些工具；</li>
 *   <li>{@link #callTool(String, Map)}：调用某个工具。</li>
 * </ul>
 *
 * <p>它只依赖 {@link Transport} 接口，不知道底层是进程内、stdio 还是 HTTP—
 * 这让 Client 与传输方式解耦。id 用 {@link AtomicLong} 自增，保证并发下唯一。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class McpClient {

    /** Client 声明的协议版本 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final Transport transport;
    private final ObjectMapper objectMapper;

    /** 请求 id 生成器，自增保证唯一 */
    private final AtomicLong idGen = new AtomicLong(0);

    /** 是否已完成初始化握手 */
    private volatile boolean initialized = false;

    /**
     * 构造器注入传输层与 JSON 映射器。
     *
     * @param transport    传输层（本项目为 InProcessTransport）
     * @param objectMapper Jackson 映射器，用于把 result 转成强类型
     */
    public McpClient(Transport transport, ObjectMapper objectMapper) {
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    /**
     * initialize 握手：声明 Client 能力，接收 Server 能力。
     *
     * <p>握手成功后再发一条 notifications/initialized 通知，符合 MCP 生命周期。</p>
     *
     * @return Server 返回的能力信息（原始 Map）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> initialize() {
        Map<String, Object> params = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agentstudy-mcp-client", "version", "1.0.0")
        );
        JsonRpcRequest req = JsonRpcRequest.of(nextId(), McpMethods.INITIALIZE, params);
        JsonRpcResponse resp = transport.send(req);
        checkError(resp, "initialize");

        // 握手完，补发 initialized 通知（无 id，不等响应）
        transport.send(JsonRpcRequest.notification(McpMethods.INITIALIZED, null));
        initialized = true;
        log.info("[McpClient] 初始化握手完成");
        return (Map<String, Object>) resp.getResult();
    }

    /**
     * tools/list：发现 Server 提供的所有工具。
     *
     * @return 工具定义列表
     */
    @SuppressWarnings("unchecked")
    public List<ToolDefinition> listTools() {
      ensureInitialized();
        JsonRpcRequest req = JsonRpcRequest.of(nextId(), McpMethods.TOOLS_LIST, null);
        JsonRpcResponse resp = transport.send(req);
        checkError(resp, "tools/list");

        Map<String, Object> result = (Map<String, Object>) resp.getResult();
        Object toolsObj = result == null ? null : result.get("tools");
        List<ToolDefinition> tools = new ArrayList<>();
        if (toolsObj instanceof List<?> rawList) {
            for (Object o : rawList) {
                // result 里的 tools 可能是 Map 也可能已是 ToolDefinition，统一用 Jackson 转换
                ToolDefinition td = objectMapper.convertValue(o, ToolDefinition.class);
                tools.add(td);
            }
        }
        log.info("[McpClient] 发现 {} 个工具: {}", tools.size(),
                tools.stream().map(ToolDefinition::getName).toList());
        return tools;
    }

    /**
     * tools/call：调用指定工具。
     *
     * @param toolName  工具名
     * @param arguments 调用参数
     * @return 工具执行结果
     */
    public CallToolResult callTool(String toolName, Map<String, Object> arguments) {
        ensureInitialized();
        Map<String, Object> params = Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        );
        JsonRpcRequest req = JsonRpcRequest.of(nextId(), McpMethods.TOOLS_CALL, params);
        JsonRpcResponse resp = transport.send(req);
        checkError(resp, "tools/call:" + toolName);

        // 把 result 转成强类型 CallToolResult
        CallToolResult result = objectMapper.convertValue(resp.getResult(), CallToolResult.class);
        log.info("[McpClient] 调用工具 {} 完成, isError={}", toolName, result.isError());
        return result;
    }

    /**
     * 确保已完成 initialize；未初始化则自动握手一次。
     *
     * <p>这体现了对使用者的友好：即使忘了先调 initialize，Client 也能自愈。</p>
     */
    private void ensureInitialized() {
        if (!initialized) {
            log.warn("[McpClient] 尚未初始化，自动执行 initialize");
            initialize();
        }
    }

    /**
     * 校验响应，若为协议级错误则抛出运行时异常。
     *
     * @param resp   响应
     * @param action 动作描述（用于报错信息）
     */
    private void checkError(JsonRpcResponse resp, String action) {
        if (resp == null) {
            throw new IllegalStateException("[McpClient] " + action + " 未收到响应");
        }
        if (resp.isError()) {
            throw new IllegalStateException("[McpClient] " + action + " 失败: "
                    + resp.getError().getCode() + " " + resp.getError().getMessage());
        }
    }

    /** 生成下一个请求 id */
    private long nextId() {
        return idGen.incrementAndGet();
    }
}