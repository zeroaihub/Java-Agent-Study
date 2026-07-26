package com.zero.ai.agentstudy.day07mcp.service;

import com.zero.ai.agentstudy.day07mcp.dto.McpAgentResponse;
import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.entity.ToolDefinition;
import com.zero.ai.agentstudy.day07mcp.mcp.client.McpClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * McpAgentService —— MCP Agent V1 的大脑。
 *
 * <p>教学要点：这是把前面所有零件串起来的「总装车间」。一个 Agent 的最小闭环是：</p>
 * <ol>
 *   <li><b>感知</b>：启动时通过 McpClient 的 tools/list 自动发现有哪些工具（不硬编码）；</li>
 *   <li><b>决策</b>：根据用户输入选择合适的工具并抽取参数；</li>
 *   <li><b>行动</b>：通过 McpClient 的 tools/call 调用工具；</li>
 *   <li><b>回复</b>：把工具结果整理成自然语言回答。</li>
 * </ol>
 *
 * <p>关键价值——<b>新增工具无需改本类</b>：工具发现是动态的（listTools），
 * 决策部分我们用「关键词规则」做轻量意图识别（真实项目会换成 LLM 的 function calling，
 * 但决策入口和调用出口都不变）。这就是 MCP 带来的「Agent 与工具彻底解耦」。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Service
public class McpAgentService {

    private final McpClient mcpClient;

    /** Agent 启动时缓存的可用工具清单（来自 tools/list） */
    private List<ToolDefinition> availableTools;

    /** 匹配「数字 运算符 数字」的简易表达式，如 12 + 8 */
    private static final Pattern CALC_PATTERN =
            Pattern.compile("(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/xX×÷])\\s*(-?\\d+(?:\\.\\d+)?)");

    public McpAgentService(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * 应用启动后自动发现工具（感知阶段）。
     *
     * <p>这里体现「Agent 不硬编码工具」：它先握手，再拉取工具清单，
     * 之后 Server 端新增/下线工具，Agent 重启即自动感知。</p>
     */
    @PostConstruct
    public void discoverTools() {
        try {
            mcpClient.initialize();
            this.availableTools = mcpClient.listTools();
            log.info("[McpAgentService] Agent 启动，已发现工具: {}",
                    availableTools.stream().map(ToolDefinition::getName).toList());
        } catch (Exception e) {
            log.error("[McpAgentService] 工具发现失败", e);
        }
    }

    /**
     * Agent 处理一条用户输入（决策 + 行动 + 回复）。
     *
     * @param userInput 用户自然语言输入
     * @return Agent 的回答
     */
    public McpAgentResponse chat(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return McpAgentResponse.fail("输入不能为空");
        }
        String input = userInput.trim();
        log.info("[McpAgentService] 收到用户输入: {}", input);

        try {
            // ===== 决策阶段：基于关键词的轻量意图识别 =====
            // 1) 计算意图：命中「数字 运算符 数字」
            Matcher m = CALC_PATTERN.matcher(input);
            if (input.contains("算") || input.contains("计算") || m.find()) {
                return handleCalculate(input);
            }
            // 2) 时间意图
            if (input.contains("时间") || input.contains("几点") || input.contains("日期")) {
                return handleTime(input);
            }
            // 3) 天气意图
            if (input.contains("天气") || input.contains("气温") || input.contains("下雨")) {
                return handleWeather(input);
            }
        // 4) 兜底：无法匹配工具
            return McpAgentResponse.builder()
                    .success(true)
                    .toolUsed(null)
                    .answer("我暂时无法理解你的需求。可用能力：查天气、查时间、做四则运算。")
                    .message("no-tool-matched")
                    .build();
        } catch (Exception e) {
            log.error("[McpAgentService] 处理失败", e);
            return McpAgentResponse.fail("处理异常: " + e.getMessage());
        }
    }

    /**
     * 处理计算意图：抽取表达式并调用 calculate 工具。
     */
    private McpAgentResponse handleCalculate(String input) {
        Matcher m = CALC_PATTERN.matcher(input);
        if (!m.find()) {
            return McpAgentResponse.fail("没有识别到可计算的表达式，请输入如「12 + 8」");
        }
        double a = Double.parseDouble(m.group(1));
        String opSymbol = m.group(2);
        double b = Double.parseDouble(m.group(3));
        String op = switch (opSymbol) {
            case "+" -> "add";
            case "-" -> "subtract";
            case "*", "x", "X", "×" -> "multiply";
            case "/", "÷" -> "divide";
            default -> null;
        };
        if (op == null) {
            return McpAgentResponse.fail("不支持的运算符: " + opSymbol);
        }
        Map<String, Object> args = new HashMap<>();
        args.put("op", op);
        args.put("a", a);
        args.put("b", b);
        CallToolResult result = mcpClient.callTool("calculate", args);
        return toResponse("calculate", result);
    }

    /**
     * 处理时间意图：尝试抽取时区，调用 get_current_time。
     */
    private McpAgentResponse handleTime(String input) {
        Map<String, Object> args = new HashMap<>();
        // 简单示例：识别常见城市 → 时区
        if (input.contains("纽约")) {
            args.put("timezone", "America/New_York");
        } else if (input.contains("伦敦")) {
            args.put("timezone", "Europe/London");
        } else if (input.contains("东京")) {
            args.put("timezone", "Asia/Tokyo");
        }
        CallToolResult result = mcpClient.callTool("get_current_time", args);
        return toResponse("get_current_time", result);
    }

    /**
     * 处理天气意图：抽取城市名，调用 get_weather。
     */
    private McpAgentResponse handleWeather(String input) {
        String city = extractCity(input);
        if (city == null) {
            return McpAgentResponse.fail("没有识别到城市名，请问你想查哪个城市的天气？");
        }
        Map<String, Object> args = new HashMap<>();
        args.put("city", city);
        CallToolResult result = mcpClient.callTool("get_weather", args);
        return toResponse("get_weather", result);
    }

    /**
     * 从输入中抽取城市名（示例用有限词典，真实项目用 NER/LLM）。
     */
    private String extractCity(String input) {
        String[] cities = {"北京", "上海", "广州", "深圳", "杭州"};
        for (String c : cities) {
            if (input.contains(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 把工具结果转成 Agent 回答：区分「业务失败(isError)」与「成功」。
     */
    private McpAgentResponse toResponse(String toolName, CallToolResult result) {
        if (result.isError()) {
            // 工具业务失败：如实告知用户，但整体流程仍算「处理完成」
            return McpAgentResponse.builder()
                    .success(false)
                    .toolUsed(toolName)
                    .answer(result.asText())
                    .message("tool-business-error")
                  .build();
        }
        return McpAgentResponse.ok(toolName, result.asText());
    }

    /** 暴露已发现的工具清单，供 Controller 查询 */
    public List<ToolDefinition> getAvailableTools() {
        return availableTools;
    }
}