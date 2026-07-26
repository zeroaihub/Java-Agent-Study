package com.zero.ai.agentstudy.day3funcall.registry;

import com.zero.ai.agentstudy.day3funcall.tool.*;
import com.zero.ai.agentstudy.day3funcall.tool.CalculatorTool03;
import com.zero.ai.agentstudy.day3funcall.tool.EmailTool03;
import com.zero.ai.agentstudy.day3funcall.tool.TimeTool03;
import com.zero.ai.agentstudy.day3funcall.tool.WeatherTool03;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 / 工具目录（第七章：企业最佳实践）
 *
 * 解决的核心问题：当工具从 3 个增长到几十上百个时，
 * 不能每次都手工把工具一个个传进 chat()。需要一个"目录"来分组、按场景取用。
 *
 * 设计思路：
 *   1. 按"业务域/场景"给工具分组（group）。
 *   2. 对外提供 getToolsByGroup(...)，编排层按场景取一组工具挂载给 LLM。
 *   3. 只把"当前场景相关"的工具交给 LLM，避免 prompt 膨胀、选择变差。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry03 {

    private final WeatherTool03 weatherTool03;
    private final TimeTool03 timeTool03;
    private final CalculatorTool03 calculatorTool03;
    private final EmailTool03 emailTool03;

    /** 工具分组：group 名 -> 该组下的工具对象列表 */
    private final Map<String, List<Object>> groups = new LinkedHashMap<>();

    /**
     * 应用启动后初始化工具目录。
     * 真实企业里，这里可能改为从配置中心 / 数据库动态加载分组关系。
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        // 场景1：日常助手（天气、时间、计算）
        groups.put("assistant", List.of(weatherTool03, timeTool03, calculatorTool03));
        // 场景2：通知/办公（时间、邮件）
        groups.put("office", List.of(timeTool03, emailTool03));
        // 场景3：全部工具（谨慎使用，工具多时会撑大 prompt）
        groups.put("all", List.of(weatherTool03, timeTool03, calculatorTool03, emailTool03));

        log.info("[ToolRegistry] 工具目录初始化完成, 分组={}", groups.keySet());
    }

    /**
     * 按场景分组获取工具。
     *
     * @param group 分组名，如 assistant / office / all
     * @return 该组工具对象数组（可直接传给 ChatClient.tools(...)）
     */
    public Object[] getToolsByGroup(String group) {
        List<Object> tools = groups.getOrDefault(group, groups.get("assistant"));
        log.info("[ToolRegistry] 取用分组={}, 工具数={}", group, tools.size());
        return tools.toArray();
    }

    /**
     * 列出所有分组及其工具数，便于运维查看"目录"。
     */
    public Map<String, Integer> listGroups() {
        Map<String, Integer> result = new LinkedHashMap<>();
        groups.forEach((k, v) -> result.put(k, v.size()));
        return result;
    }
}