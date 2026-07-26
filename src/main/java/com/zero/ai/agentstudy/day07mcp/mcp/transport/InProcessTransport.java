package com.zero.ai.agentstudy.day07mcp.mcp.transport;

import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcRequest;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcResponse;
import com.zero.ai.agentstudy.day07mcp.mcp.server.McpServer;
import com.zero.ai.agentstudy.day07mcp.util.McpTraceLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * InProcessTransport —— 进程内直连传输实现。
 *
 * <p>教学要点：真实 MCP 用 stdio 或 HTTP 跨进程通信，但那会引入进程管理、
 * 序列化、网络等复杂度，掩盖协议本身的学习重点。所以 Day07 用「进程内直连」：
 * Client 把请求对象直接交给同一 JVM 内的 {@link McpServer} 处理，拿回响应。</p>
 *
 * <p>关键在于：<b>接口不变</b>。它实现的是通用 {@link Transport} 接口，
 * 与未来的 stdio / HTTP 实现签名完全一致。因此当你把学习项目升级为真实跨进程时，
 * 只需替换这一个类，Client 侧码零改动——这正是「依赖倒置」带来的可替换性。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class InProcessTransport implements Transport {

    /** 传输类型标识 */
    private static final String TYPE = "in-process";

    private final McpServer mcpServer;
    private final McpTraceLogger traceLogger;

    /**
     * 构造器注入 Server 与日志器。
     *
     * @param mcpServer   进程内的 MCP Server
     * @param traceLogger 链路日志器
     */
    public InProcessTransport(McpServer mcpServer, McpTraceLogger traceLogger) {
        this.mcpServer = mcpServer;
        this.traceLogger = traceLogger;
    }

    @Override
    public JsonRpcResponse send(JsonRpcRequest request) {
        // 记录发送埋点（在真实传输里，这之后就是写管道/发 HTTP）
        traceLogger.logSend(TYPE, request.getMethod(), request.getId());

        // 进程内直连：把请求直接交给 Server 处理
        // （真实 stdio 会在这里把 request 序列化成 JSON 写入子进程 stdin，
        //   再从 stdout 读回 JSON 反序列化成 Response）
        JsonRpcResponse response = mcpServer.handle(request);

        if (request.isNotification()) {
            log.debug("[InProcessTransport] 通知类请求 method={}，无响应", request.getMethod());
        }
        return response;
    }

    @Override
    public String type() {
        return TYPE;
    }
}