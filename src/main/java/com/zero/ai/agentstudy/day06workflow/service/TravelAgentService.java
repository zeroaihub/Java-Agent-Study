package com.zero.ai.agentstudy.day06workflow.service;

import com.zero.ai.agentstudy.day06workflow.dto.TravelResponse;
import com.zero.ai.agentstudy.day06workflow.workflow.context.ContextKeys;
import com.zero.ai.agentstudy.day06workflow.workflow.context.WorkflowContext;
import com.zero.ai.agentstudy.day06workflow.workflow.core.WorkflowNode;
import com.zero.ai.agentstudy.day06workflow.workflow.engine.WorkflowEngine;
import com.zero.ai.agentstudy.day06workflow.workflow.model.WorkflowResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TravelAgentService —— Travel Agent 的业务服务层。
 *
 * <p>教学要点：Service 负责「组装一次流程运行」：
 * 创建 Context、放入用户输入、把节点链交给引擎跑、再把引擎结果转成 DTO。
 * 它是 Controller 与 Workflow 引擎之间的桥梁，符合分层架构。</p>
 *
 * @author ZeroAi
 */
@Slf4j
@Service
public class TravelAgentService {

    private final WorkflowEngine engine;
    private final List<WorkflowNode> travelNodes;

    /**
     * 注入引擎与「旅行流程节点链」（由 TravelWorkflowConfig 装配）。
     *
     * @param engine      工作流引擎
     * @param travelNodes 有序节点链
     */
    public TravelAgentService(WorkflowEngine engine,
                              @Qualifier("travelWorkflowNodes") List<WorkflowNode> travelNodes) {
        this.engine = engine;
        this.travelNodes = travelNodes;
    }

    /**
     * 处理一次旅行规划请求。
     *
     * @param userInput 用户自然语言输入，如「我想去杭州玩三天」
     * @return 规划结果 DTO
     */
    public TravelResponse plan(String userInput) {
        WorkflowContext context = new WorkflowContext();
        context.put(ContextKeys.USER_INPUT, userInput);

        WorkflowResult result = engine.run(travelNodes, context);

        return new TravelResponse(
                result.getRunId(),
                result.getState().name(),
                result.getOutput() != null ? result.getOutput() : "（未生成方案）",
                result.logsAsText()
        );
    }
}