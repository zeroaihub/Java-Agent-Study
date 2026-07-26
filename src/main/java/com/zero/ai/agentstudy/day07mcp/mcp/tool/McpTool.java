package com.zero.ai.agentstudy.day07mcp.mcp.tool;

import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.entity.ToolDefinition;

import java.util.Map;

/**
 * McpTool —— MCP 工具的统一抽象口（Server 侧最重要的扩展点）。
 *
 * <p>教学要点：这是整个 MCP Server 的「开闭原则(OCP)」落地点。
 * Server 只依赖这个接口；想新增一个工具（查汇率、发邮件……），只需新增一个
 * implements McpTool 的类并交给 Spring 管理，Server / Registry 代码一行不用改。</p>
 *
 * <p>每个工具要回答三个问题：</p>
 * <ul>
 *   <li>我叫什么？（{@link #name()}）——模型调用时的唯一标识；</li>
 *   <li>我能干什么、参数长什么样？（{@link #definition()}）——给模型读的元数据；</li>
 *   <li>怎么执行？（{@link #execute(Map)}）——真正的业务逻辑。</li>
 * </ul>
 *
 * @author ZeroAi
 */
public interface McpTool {

    /**
     * 工具唯一名称，供 tools/call 通过 name 定位。
     *
     * @return 工具名，如 "get_weather"
     */
    String name();

    /**
     * 工具的自我描述（名称、说明、入参 JSON Schema）。
     *
     * <p>tools/list 会收集所有工具的 definition 返回给 Client/模型，
     * 让模型知道有哪些工具、各自参数是什么。</p>
     *
     * @return 工具定义
     */
    ToolDefinition definition();

    /**
     * 执行工具。
     *
     * <p>约定：</p>
     * <ul>
     *   <li>入参已由 Server 从 tools/call 的 arguments 解析出来；</li>
     *   <li>业务成功返回 {@link CallToolResult#ok}；</li>
     *   <li>业务失败（如参数值不合理）返回 {@link CallToolResult#fail}，
     *       而不是抛异常——把「是否重试」的决定权交给上层/模型。</li>
     * </ul>
     *
     * @param arguments 调用参数（key=参数名）
     * @return 工具执行结果
     */
    CallToolResult execute(Map<String, Object> arguments);
}