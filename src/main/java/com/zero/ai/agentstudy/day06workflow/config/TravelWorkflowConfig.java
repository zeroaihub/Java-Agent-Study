package com.zero.ai.agentstudy.day06workflow.config;

import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowNode;
import com.zero.ai.agentstudy.day06workflow.workflow.node.HotelNode;
import com.zero.ai.agentstudy.day06workflow.workflow.node.InputCityNode;
import com.zero.ai.agentstudy.day06workflow.workflow.node.OutputNode;
import com.zero.ai.agentstudy.day06workflow.workflow.node.PlanNode;
import com.zero.ai.agentstudy.day06workflow.workflow.node.WeatherNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * TravelWorkflowConfig —— 旅行工作流的「编排配置」。
 *
 * <p>教学要点：节点的「串联顺序」不写死在代码逻辑里，而是集中在配置类声明。
 * 想调整流程（比如把查酒店提前、或插入查机票节点），只改这里的 List 顺序即可，
 * 引擎和节点都不用动。这就是「配置化编排」的雏形，
 * 真实平台会做成可视化拖拽 / JSON 配置。</p>
 *
 * @author ZeroAi
 */
@Configuration
public class TravelWorkflowConfig {

    /**
     * 装配旅行规划的节点责任链（有序）。
     *
     * @return 按执行顺序排列的节点列表
     */
    @Bean("travelWorkflowNodes")
    public List<WorkflowNode> travelWorkflowNodes(InputCityNode inputCityNode,
                                                  WeatherNode weatherNode,
                                                  HotelNode hotelNode,
                                                  PlanNode planNode,
                                                  OutputNode outputNode) {
        // 顺序即流程：解析城市 → 查天气 → 查酒店 → 生成计划 → 输出
        return List.of(inputCityNode, weatherNode, hotelNode, planNode, outputNode);
    }
}