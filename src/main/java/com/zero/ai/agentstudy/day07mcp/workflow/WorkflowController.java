package com.zero.ai.agentstudy.day07mcp.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WorkflowController —— 工作流演示的 HTTP 入口。
 *
 * <p>教学要点：提供一个端点触发「查天气 → 出行建议」工作流，方便用浏览器验证
 * 多节点编排 + MCP 工具调用的完整链路。</p>
 *
 * <ul>
 *   <li>GET /api/mcp/workflow/weather-advice?city=北京
 *   —— 运行示例工作流，返回执行链路与最终建议。</li>
 * </ul>
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp/workflow")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    /**
     * 运行「查天气 → 出行建议」工作流。
     *
     * @param city 城市名，默认北京
     * @return 工作流执行结果
     */
    @GetMapping("/weather-advice")
    public WorkflowResult weatherAdvice(@RequestParam(value = "city", defaultValue = "北京") String city) {
        log.info("[WorkflowController] 运行 weather-advice 工作流, city={}", city);
        return workflowService.runWeatherAdvice(city);
    }


    @GetMapping("/calculator-advice")
    public WorkflowResult calculatorAdvice(@RequestParam(value = "op", defaultValue = "add") String op,
                                             @RequestParam(value = "a", defaultValue = "1") String a,
                                             @RequestParam(value = "b", defaultValue = "2") String b) {
        log.info("[WorkflowController] 运行 calculator-advice 工作流, op={}, a={}, b={}", op, a, b);
        return workflowService.runCalculatorAdvice(op, a, b);
    }
}