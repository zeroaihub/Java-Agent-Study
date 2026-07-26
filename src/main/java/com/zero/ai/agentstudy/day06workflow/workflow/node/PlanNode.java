package com.zero.ai.agentstudy.day06workflow.workflow.node;

import com.zero.ai.agentstudy.day06workflow.workflow.context.ContextKeys;
import com.zero.ai.agentstudy.day06workflow.workflow.context.WorkflowContext;
import com.zero.ai.agentstudy.day06workflow.workflow.core.NodeResult;
import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PlanNode —— 第 4 个节点：综合天气与酒店，生成旅行计划。
 *
 * <p>教学要点：这是「数据加工型」节点（Pipeline 思想）：
 * 它不查外部数据，而是把上游 Node 放进 Context 的天气、酒店做二次加工，
 * 产出更高层的「计划」。</p>
 *
 * <p>为保证 Demo 无需外网可独立运行，这里用规则拼装计划；
 * 真实项目可替换成调用 LLM(Spring AI ChatClient) 生成，
 * 只需改本类，其他节点与引擎零改动——这就是可插拔的价值。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class PlanNode implements WorkflowNode {

    @Override
    public String name() {
        return "PlanNode";
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult execute(WorkflowContext context) {
        String city = context.getString(ContextKeys.CITY);
        String weather = context.getString(ContextKeys.WEATHER);
        List<String> hotels = context.get(ContextKeys.HOTELS, List.class);
        if (city == null || weather == null || hotels == null) {
            return NodeResult.fail("生成计划所需数据不完整");
        }

        StringBuilder plan = new StringBuilder();
        plan.append("根据当前 ").append(city).append(" 的天气「").append(weather).append("」，为你规划：\n");
        if (weather.contains("雨")) {
            plan.append("- 建议以室内景点为主（博物馆、美术馆），并携带雨具。\n");
        } else {
            plan.append("- 天气不错，推荐户外游览与城市漫步。\n");
        }
        plan.append("- 推荐入住：").append(hotels.get(0)).append("\n");
        plan.append("- 行程：Day1 市区经典景点；Day2 特色街区+美食；Day3 周边自然风光。");

        context.put(ContextKeys.PLAN, plan.toString());
        log.info("[PlanNode] 已生成旅行计划");
        return NodeResult.success("旅行计划生成完成");
    }
}