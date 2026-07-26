package com.zero.ai.agentstudy.day07mcp.workflow;

import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.mcp.client.McpClient;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * McpToolNode —— 「通过 MCP 调用一个工具」的通用工作流节点。
 *
 * <p>教学要点：这是本章「Workflow + MCP」的关键胶水。它把「调用某个 MCP 工具」
 * 封装成一个标准节点，从而可以像积木一样被编排进任意工作流。它只依赖
 * {@link McpClient}（复用第四章成果），完全不感知底层协议——工作流层面看到的
 * 只是「输入 → 调工具 → 产出」。</p>
 *
 * <p>为了让同一个节点适配不同工具，我们把两件事参数化：</p>
 * <ul>
 *   <li><b>argBuilder</b>：一个函数，负责「从上下文里取数据 → 拼出该工具的入参」；</li>
 *   <li><b>outputKey</b>：把工具返回的文本写回上下文的哪个 key，供下游节点读取。</li>
 * </ul>
 *
 * <p>这样，「查天气」和「算数」可以复用同一个 McpToolNode 类，只是构造参数不同——
 * 这就是用组合（而非继承）实现的复用。</p>
 *
 * @author ZeroAi
 */
@Slf4j
public class McpToolNode implements WorkflowNode {

    /** 节点名（也用于日志） */
    private final String nodeName;

    /** 要调用的 MCP 工具名，如 get_weather */
    private final String toolName;

    /** MCP 客户端（复用第四章的高层封装） */
    private final McpClient mcpClient;

    /** 参数构造器：从上下文拼出工具入参 */
    private final Function<WorkflowContext, Map<String, Object>> argBuilder;

    /** 工具产出写回上下文的 key */
    private final String outputKey;

    /**
     * 构造一个 MCP 工具节点。
     *
     * @param nodeName   节点名
     * @param toolName   MCP 工具名
     * @param mcpClient  MCP 客户端
     * @param argBuilder 从上下文构造入参的函数
     * @param outputKey  产出写回上下文的 key
     */
    public McpToolNode(String nodeName,
                       String toolName,
                       McpClient mcpClient,
                       Function<WorkflowContext, Map<String, Object>> argBuilder,
                       String outputKey) {
        this.nodeName = nodeName;
        this.toolName = toolName;
        this.mcpClient = mcpClient;
        this.argBuilder = argBuilder;
        this.outputKey = outputKey;
    }

    @Override
    public String name() {
        return nodeName;
    }

    @Override
    public boolean execute(WorkflowContext context) {
        // 1) 从上下文拼出工具入参
        Map<String, Object> args = argBuilder.apply(context);
        if (args == null) {
            args = new LinkedHashMap<>();
        }
        log.info("[McpToolNode:{}] 调用工具 {}，参数={}", nodeName, toolName, args);

        // 2) 通过 McpClient 调用 MCP 工具
        CallToolResult result = mcpClient.callTool(toolName, args);
        String text = result.asText();

        // 3) 无论成败都把文本写回上下文，方便下游/调试查看
        context.put(outputKey, text);

        // 4) 工具业务失败（isError）→ 把原因写进 error 并返回 false，让引擎中断
        if (result.isError()) {
            context.put("error", "工具[" + toolName + "]失败: " + text);
            return false;
        }
        return true;
    }
}