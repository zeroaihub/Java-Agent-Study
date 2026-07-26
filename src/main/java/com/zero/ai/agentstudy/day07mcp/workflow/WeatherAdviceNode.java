package com.zero.ai.agentstudy.day07mcp.workflow;

import lombok.extern.slf4j.Slf4j;

/**
 * WeatherAdviceNode —— 根据上游「天气结果」生成穿衣/出行建议的本地加工节点。
 *
 * <p>教学要点：这是一个「不调 MCP、纯本地逻辑」的节点，用来演示两件事：</p>
 * <ol>
 *   <li><b>节点间数据依赖</b>：它从上下文里读取上一步（{@link McpToolNode} 查天气）
 *       写入的 {@code weatherText}，再二次加工——这就是「上一步的输出=下一步的输入」；</li>
 *   <li><b>工作流的异构性</b>：同一条流水线里，节点可以是「调 MCP 工具」，也可以是
 *       「本地计算」。引擎对两者一视同仁，因为它们都实现了 {@link WorkflowNode}。</li>
 * </ol>
 *
 * <p>这解释了 Workflow 相对「单次工具调用」的价值：把多个能力（远程工具 + 本地规则）
 * 编排成一个更高层的复合能力（查天气 → 给建议）。</p>
 *
 * @author ZeroAi
 */
@Slf4j
public class WeatherAdviceNode implements WorkflowNode {

    /** 上游天气节点写入上下文的 key */
    private final String weatherKey;

    /** 本节点建议写回上下文的 key */
    private final String adviceKey;

    /**
     * 构造建议节点。
     *
     * @param weatherKey 上游天气文本的 key
     * @param adviceKey  本节点产出建议的 key
     */
    public WeatherAdviceNode(String weatherKey, String adviceKey) {
        this.weatherKey = weatherKey;
        this.adviceKey = adviceKey;
    }

    @Override
    public String name() {
        return "weather_advice";
    }

    @Override
    public boolean execute(WorkflowContext context) {
        // 1) 读取上游节点的产出（体现节点间数据传递）
        String weather = context.getString(weatherKey);
        if (weather.isEmpty()) {
            context.put("error", "缺少上游天气数据，无法生成建议");
            return false;
        }
        log.info("[WeatherAdviceNode]基于天气生成建议，天气={}", weather);

        // 2) 基于关键词做简单规则加工（真实项目可换成 LLM 生成）
        StringBuilder advice = new StringBuilder("出行建议：");
        if (weather.contains("雨")) {
            advice.append("有降雨，记得带伞；");
        }
        if (weather.contains("雷")) {
            advice.append("有雷电，尽量减少户外活动；");
        }
        if (weather.contains("晴")) {
            advice.append("天气晴好，注意防晒；");
        }
        if (weather.contains("阴") || weather.contains("多云")) {
            advice.append("云量较多，适合出行；");
        }
        advice.append("请根据气温增减衣物。");

        // 3) 把建议写回上下文（作为整条流程的最终产出之一）
        context.put(adviceKey, advice.toString());
        return true;
    }
}