package com.zero.ai.agentstudy.day06workflow.workflow.node;

import com.zero.ai.agentstudy.day06workflow.tool.HotelService;
import com.zero.ai.agentstudy.day06workflow.workflow.context.ContextKeys;
import com.zero.ai.agentstudy.day06workflow.workflow.context.WorkflowContext;
import com.zero.ai.agentstudy.day06workflow.workflow.core.NodeResult;
import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HotelNode —— 第 3 个节点：根据城市查酒店（多源聚合）。
 *
 * <p>教学要点：这里演示 Node 编排「策略模式聚合服务」{@link HotelService}。
 * Node 无需知道有几个数据源，OCP 体现在服务层。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotelNode implements WorkflowNode {

    private final HotelService hotelService;

    @Override
    public String name() {
        return "HotelNode";
    }

    @Override
    public NodeResult execute(WorkflowContext context) {
        String city = context.getString(ContextKeys.CITY);
        if (city == null) {
            return NodeResult.fail("Context 中缺少 city，无法查酒店");
        }
        List<String> hotels = hotelService.searchAll(city);
        context.put(ContextKeys.HOTELS, hotels);
        return NodeResult.success("查到 " + hotels.size() + " 家酒店");
    }
}