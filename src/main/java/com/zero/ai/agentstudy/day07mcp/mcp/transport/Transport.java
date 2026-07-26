package com.zero.ai.agentstudy.day07mcp.mcp.transport;

import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcRequest;
import com.zero.ai.agentstudy.day07mcp.mcp.protocol.JsonRpcResponse;

/**
 * Transport —— MCP 的传输层抽象。
 *
 * <p>教学要点：MCP 把「传什么」（JSON-RPC 报文）和「怎么传」（传输方式）彻底分开。
 * 官方支持两种标准传输：</p>
 * <ul>
 *   <li><b>stdio</b>：本地进程，通过标准输入/输出管道通信；</li>
 *   <li><b>Streamable HTTP / SSE</b>：远程服务，通过 HTTP 通信。</li>
 * </ul>
 *
 * <p>无论哪种，Client 眼里都只是「我发一个 Request，拿回一个 Response」。
 * 于是我们抽象出这个接口，本项目提供 {@link InProcessTransport}（进程内直连）实现，
 * 未来要换成 stdio 或 HTTP，只需新增一个实现类，Client 代码一行不改——
 * 这就是「面向接口编程 + 依赖倒置」的价值。</p>
 *
 * @author ZeroAi
 */
public interface Transport {

    /**
     * 发送一个 JSON-RPC 请求并同步获取响应。
     *
     * <p>注意：如果传入的是「通知」（无 id），按 JSON-RPC 规范服务端不应答复，
     * 此时实现可返回 null。</p>
     *
     * @param request JSON-RPC 请求
     * @return JSON-RPC 响应；通知类请求返回 null
     */
    JsonRpcResponse send(JsonRpcRequest request);

    /**
     * 传输方式名称，便于日志与调试。
     *
     * @return 如 "in-process" / "stdio" / "http"
     */
    String type();
}