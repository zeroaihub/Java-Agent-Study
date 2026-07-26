package com.zero.ai.agentstudy.day3funcall.controller;

import com.zero.ai.agentstudy.day3funcall.assistant.AgentAssistantV1;
import com.zero.ai.agentstudy.day3funcall.registry.ToolRegistry03;
import com.zero.ai.agentstudy.day3funcall.service.Day3AgentService;
import com.zero.ai.agentstudy.day3funcall.tool.*;
import com.zero.ai.agentstudy.day3funcall.tool.CalculatorTool03;
import com.zero.ai.agentstudy.day3funcall.tool.EmailTool03;
import com.zero.ai.agentstudy.day3funcall.tool.TimeTool03;
import com.zero.ai.agentstudy.day3funcall.tool.WeatherTool03;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Day3 Function Calling 测试入口（第四、五、六章）
 *
 * 启动应用后，浏览器访问：
 *   单工具:  http://localhost:8080/day3/weather?msg=北京今天天气怎么样
 *   多工具:  http://localhost:8080/day3/agent?msg=帮我算一下 1234 乘以 5678
 *   协同:    http://localhost:8080/day3/workflow?msg=查一下杭州天气，把结果发邮件到 boss@example.com
 *
 * 观察控制台日志：会看到对应 [XxxTool] 被调用。
 *
 * @author ZeroAi
 */
@RestController
@RequestMapping("/day3")
@RequiredArgsConstructor
public class Day3Controller {

    private final Day3AgentService day3AgentService;
    private final WeatherTool03 weatherTool03;
    private final TimeTool03 timeTool03;
    private final CalculatorTool03 calculatorTool03;
    private final EmailTool03 emailTool03;
    private final ToolRegistry03 toolRegistry;
    private final AgentAssistantV1 agentAssistantV1;

    /** 单工具（天气）测试。 */
    @GetMapping("/weather")
    public String weather(@RequestParam(defaultValue = "北京今天天气怎么样？") String msg) {
        return day3AgentService.chatWithWeather(msg);
    }

    /** 多工具测试：天气/时间/计算器，由 LLM 自动选择。 */
    @GetMapping("/agent")
    public String agent(@RequestParam(defaultValue = "现在几点？顺便算下 12 加 8") String msg) {
        return day3AgentService.chat(msg, weatherTool03, timeTool03, calculatorTool03);
    }

    /** 多工具协同（Workflow 雏形）：Weather + Email，工具间有数据依赖。 */
    @GetMapping("/workflow")
    public String workflow(
            @RequestParam(defaultValue = "查一下杭州今天的天气，把结果发邮件到 boss@example.com") String msg) {
        return day3AgentService.chat(msg, weatherTool03, emailTool03);
    }

    /** 第七章：按工具目录分组挂载工具（assistant/office/all）。 */
    @GetMapping("/group")
    public String group(
            @RequestParam(defaultValue = "assistant") String group,
            @RequestParam(defaultValue= "现在几点？顺便算下 3 乘以 7") String msg) {
        return day3AgentService.chat(msg, toolRegistry.getToolsByGroup(group));
    }

    /** 第七章：查看工具目录（各分组及工具数）。 */
    @GetMapping("/tools")
    public Object tools() {
        return toolRegistry.listGroups();
    }

    /** 第八章收官：Agent Assistant V1（自动选工具的完整 Agent）。 */
    @GetMapping("/assistant")
    public String assistant(
            @RequestParam(defaultValue = "北京今天天气如何？现在几点？再帮我算 88 乘以 9") String msg) {
        return agentAssistantV1.ask(msg);
    }
}
