package com.zero.ai.agentstudy.day07mcp.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * McpTraceLogger —— MCP 调用链路日志器。
 *
 * <p>教学要点：企业级 Agent 最痛的问题之一是「链路黑盒」——一次用户提问背后，
 * 模型选了哪个工具、传了什么参数、工具返回了什么、耗时多少，全靠日志还原。
 * 我们把这些「链路埋点」集中到一个组件里，Client / Server 只需在关键节点调用它，
 * 就能得到统一、可读的调用链日志。</p>
 *
 * <p>这体现了「横切关注点分离」——日志是横切逻辑，不该散落在业务代码里。
 * 生产环境可进一步升级为 OpenTelemetry / SkyWalking 的分布式链路追踪。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class McpTraceLogger {

    /**
     * 记录一次「发送请求」。
     *
     * @param transportType 传输类型
     * @param method        方法名
     * @param id            请求 id
     */
    public void logSend(String transportType, String method, Object id) {
        log.info("[MCP-TRACE] >>> 发送请求 | transport={} | method={} | id={}",
                transportType, method, id);
    }

    /**
     * 记录一次「收到请求」（Server 侧）。
     *
     * @param method 方法名
     * @param id     请求 id
     */
    public void logReceive(String method, Object id) {
        log.info("[MCP-TRACE] <<< 收到请求 | method={} | id={}", method, id);
    }

    /**
     * 记录一次工具调用开始。
     *
     * @param toolName 工具名
     * @param args     参数
     */
    public void logToolStart(String toolName, Object args) {
        log.info("[MCP-TRACE] ▶ 执行工具 | tool={} | args={}", toolName, args);
    }

    /**
     * 记录一次工具调用结束。
     *
     * @param toolName 工具名
     * @param isError  是否业务失败
     * @param costMs   耗时（毫秒）
     * @param preview  结果预览（截断）
     */
    public void logToolEnd(String toolName, boolean isError, long costMs, String preview) {
        log.info("[MCP-TRACE] ◀ 工具完成 | tool={} | isError={} | cost={}ms | result={}",
                toolName, isError, costMs, truncate(preview));
    }

    /**
     * 记录一次响应返回。
     *
     * @param method 方法名
     * @param id     请求 id
     * @param error  是否错误响应
     */
    public void logResponse(String method, Object id, boolean error) {
        log.info("[MCP-TRACE] === 返回响应 | method={} | id={} | error={}", method, id, error);
    }

    /** 结果预览截断，避免日志过长 */
    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        int max = 200;
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }
}