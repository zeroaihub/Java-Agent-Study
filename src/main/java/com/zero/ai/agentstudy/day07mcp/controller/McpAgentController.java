package com.zero.ai.agentstudy.day07mcp.controller;

import com.zero.ai.agentstudy.day07mcp.dto.McpAgentResponse;
import com.zero.ai.agentstudy.day07mcp.dto.McpCallRequest;
import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.entity.ToolDefinition;
import com.zero.ai.agentstudy.day07mcp.mcp.client.McpClient;
import com.zero.ai.agentstudy.day07mcp.service.McpAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * McpAgentController —— Day07 MCP 的 HTTP 入口。
 *
 * <p>教学要点：提供三个端点，覆盖 MCP 的三个核心动作，方便你用浏览器/Postman 验证：</p>
 * <ul>
 *   <li>GET  /api/mcp/tools     —— 查看 Agent 发现的工具清单（对应 tools/list）；</li>
 *   <li>POST /api/mcp/call      —— 直接指定工具名调用（对应 tools/call，绕过意图识别）；</li>
 *   <li>GET  /api/mcp/chat      —— 自然语言对话，由 Agent 自动选工具（完整闭环）。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpAgentController {

    private final McpAgentService agentService;
    private final McpClient mcpClient;

    public McpAgentController(McpAgentService agentService, McpClient mcpClient) {
        this.agentService = agentService;
        this.mcpClient = mcpClient;
    }

    /**
     * 查看 Agent 已发现的工具清单。
     *
     * @return 工具定义列表
     */
    @GetMapping("/tools")
    public List<ToolDefinition> tools() {
        return agentService.getAvailableTools();
    }

    /**
     * 直接指定工具名调用（用于验证 tools/call 底层链路）。
     *
     * @param request 含 toolName 与 arguments
     * @return 工具执行结果
     */
    @PostMapping("/call")
    public CallToolResult call(@RequestBody McpCallRequest request) {
        log.info("[McpAgentController] 直接调用工具: {}", request.getToolName());
        return mcpClient.callTool(request.getToolName(), request.getArguments());
    }

    /**
     * 自然语言对话入口（Agent 自动选工具的完整闭环）。
     *
     * @param message 用户输入
     * @return Agent 回答
     */
    @GetMapping("/chat")
    public McpAgentResponse chat(@RequestParam("message") String message) {
        return agentService.chat(message);
    }
}