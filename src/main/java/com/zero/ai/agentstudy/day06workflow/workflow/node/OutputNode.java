package com.zero.ai.agentstudy.day06workflow.workflow.node;

import com.zero.ai.agentstudy.day06workflow.workflow.context.ContextKeys;
import com.zero.ai.agentstudy.day06workflow.workflow.context.WorkflowContext;
import com.zero.ai.agentstudy.day06workflow.workflow.core.NodeResult;
import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OutputNode —— 第 5 个（末尾）节点：把所有中间结果汇总成 Markdown 输出。
 *
 * <p>教学要点：作为责任链最后一环，它返回 {@link NodeResult#completed}，
 * 告诉引擎「流程到此正常结束」。它体现「关注点分离」：
 * 前面节点各管一段数据，输出格式化统一收口在这里。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class OutputNode implements WorkflowNode {

    @Override
    public String name() {
        return "OutputNode";
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult execute(WorkflowContext context) {
        String city = context.getString(ContextKeys.CITY);
        String weather = context.getString(ContextKeys.WEATHER);
        List<String> hotels = context.get(ContextKeys.HOTELS, List.class);
        String plan = context.getString(ContextKeys.PLAN);

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(city).append(" 旅行方案\n\n");
        sb.append("## 天气\n").append(weather).append("\n\n");
        sb.append("## 推荐酒店\n");
        if (hotels != null) {
            hotels.forEach(h -> sb.append("- ").append(h).append("\n"));
        }
        sb.append("\n## 旅行计划\n").append(plan).append("\n");

        String output = sb.toString();
        context.put(ContextKeys.OUTPUT, output);
        log.info("[OutputNode] 最终输出已生成");
        return NodeResult.completed("旅行方案输出完成");
    }
}