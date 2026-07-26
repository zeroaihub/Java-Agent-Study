package com.zero.ai.agentstudy.day07mcp.workflow;

import com.zero.ai.agentstudy.day07mcp.mcp.client.McpClient;
import com.zero.ai.agentstudy.day07mcp.mcp.tool.CalculatorTool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * WorkflowService —— 工作流编排的「装配车间」与对外服务入口。
 *
 * <p>教学要点：本类负责把一个个 {@link WorkflowNode} 组合成有业务意义的 {@link Workflow}。
 * 它复用第四章的 {@link McpClient} 调 MCP 工具，复用第六章的编排引擎，
 * 完全没有改动 MCP 协议层任何代码——这正是「工作流层构建在 MCP 之上」的解耦体现。</p>
 *
 * <p>内置示例流程 <b>weather-advice</b>：查天气 → 生成出行建议。
 * 它由两个异构节点串成：</p>
 * <ol>
 *   <li>{@link McpToolNode}：调 MCP 的 get_weather 工具，产出 weatherText；</li>
 *   <li>{@link WeatherAdviceNode}：读 weatherText，本地生成 advice。</li>
 * </ol>
 *
 * @author ZeroAi
 */
@Slf4j
@Service
public class WorkflowService {

    /** 上下文里天气结果的 key */
    private static final String KEY_WEATHER = "weatherText";


    /** 上下文里计算结果的 key */
    private static final String KEY_CALCULATOR_RES = "calculatorRes";

    /** 上下文里建议结果的 key */
    private static final String KEY_ADVICE = "advice";

    private final McpClient mcpClient;

    public WorkflowService(McpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * 应用启动后确保 MCP 握手完成（工作流里的 MCP 节点依赖它）。
     */
    @PostConstruct
    public void init() {
        try {
            mcpClient.initialize();
            log.info("[WorkflowService] 已就绪，可运行工作流");
        } catch (Exception e) {
            log.error("[WorkflowService] MCP 初始化失败", e);
        }
    }

    /**
     * 运行「查天气 → 出行建议」工作流。
     *
     * @param city 城市名
     * @return 工作流执行结果（含中间产出与最终建议）
     */
    public WorkflowResult runWeatherAdvice(String city) {
        // 1) 构造上下文，塞入初始输入
        WorkflowContext context = new WorkflowContext().withInput("city", city);

        // 2) 声明式地把节点编排成一条工作流
        Workflow workflow = new Workflow("weather-advice")
                // 节点1：调 MCP get_weather，入参 city 从上下文取，产出写入 weatherText
                .addNode(new McpToolNode(
                        "query_weather",
                        "get_weather",
                        mcpClient,
                        ctx -> Map.of("city", String.valueOf(ctx.getInput("city"))),
                        KEY_WEATHER))
                // 节点2：本地节点，读 weatherText 生成 advice
                .addNode(new WeatherAdviceNode(KEY_WEATHER, KEY_ADVICE));

        // 3) 执行并返回结果
        return workflow.run(context);
    }

    /**
     * 运行「运算 → 判断大小」工作流。
     */
    public WorkflowResult runCalculatorAdvice(String op, String a, String b) {
        // 1) 构造上下文，塞入初始输入
        WorkflowContext context = new WorkflowContext()
                .withInput("op", op)
                .withInput("a", a)
                .withInput("b", b);

        // 2) 声明式地把节点编排成一条工作流
        Workflow workflow = new Workflow("calculator-advice")
                // 节点1：调 MCP get_weather，入参 city 从上下文取，产出写入 weatherText
                .addNode(new McpToolNode(
                        "calculator_cas",
                        CalculatorTool.TOOL_NAME,
                        mcpClient,
                        ctx -> Map.of("op", String.valueOf(ctx.getInput("op")),
                                "a", String.valueOf(ctx.getInput("a")),
                                "b", String.valueOf(ctx.getInput("b"))),
                        KEY_CALCULATOR_RES))
                // 节点2：本地节点，读 weatherText 生成 advice
                .addNode(new CalculatorAdviceNode(KEY_CALCULATOR_RES, KEY_ADVICE));

        // 3) 执行并返回结果
        return workflow.run(context);
    }
}