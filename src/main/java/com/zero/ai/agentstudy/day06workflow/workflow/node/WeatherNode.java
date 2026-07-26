package com.zero.ai.agentstudy.day06workflow.workflow.node;

import com.zero.ai.agentstudy.day06workflow.tool.WeatherService;
import com.zero.ai.agentstudy.day06workflow.workflow.context.ContextKeys;
import com.zero.ai.agentstudy.day06workflow.workflow.context.WorkflowContext;
import com.zero.ai.agentstudy.day06workflow.workflow.core.NodeResult;
import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WeatherNode —— 第 2 个节点：根据城市查天气。
 *
 * <p>教学要点：节点(Node)只做编排——从 Context 取 city，调工具，写回结；
 * 真正查天气的活交给 {@link WeatherService}（工具）。职责分离。</p>
 *
 * <p>支持重试：覆盖 maxRetries()=2，供引擎在第六章的重试机制中使用。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherNode implements WorkflowNode {

    private final WeatherService weatherService;

    @Override
    public String name() {
        return "WeatherNode";
    }

    @Override
    public NodeResult execute(WorkflowContext context) {
        String city = context.getString(ContextKeys.CITY);
        if (city == null) {
            return NodeResult.fail("Context 中缺少 city，无法查天气");
        }
        String weather = weatherService.query(city);
        context.put(ContextKeys.WEATHER, weather);
        return NodeResult.success(city + " 天气: " + weather);
    }

    @Override
    public int maxRetries() {
        return 2;
    }
}