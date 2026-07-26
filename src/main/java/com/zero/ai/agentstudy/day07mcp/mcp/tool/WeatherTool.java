package com.zero.ai.agentstudy.day07mcp.mcp.tool;

import com.zero.ai.agentstudy.day07mcp.entity.CallToolResult;
import com.zero.ai.agentstudy.day07mcp.entity.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * WeatherTool —— 查询城市天气的 MCP 工具。
 *
 * <p>教学要点：这是「新增一个工具 = 新增一个 @Component」的最直接示范。
 * 本类只关心自己的三件事（name / definition / execute），
 * 完全不知道 Server、Registry、Transport 的存在——这就是解耦。</p>
 *
 * <p>为演示方便，天气数据用内存 Map 模拟；真实项目里这里会去调气象 API。
 * 注意：无论数据来自哪里，对外暴露的接口都不变，这正是「工具内部实现可自由替换」的好处。</p>
 *
 * @author ZeroAi
 */
@Component
public class WeatherTool implements McpTool {

    /** 模拟的城市天气库（真实项目替换为外部 API 调用即可） */
    private static final Map<String, String> MOCK_WEATHER = new HashMap<>();

    static {
        MOCK_WEATHER.put("北京", "晴，26℃，东北风3级");
        MOCK_WEATHER.put("上海", "多云，28℃，东南风2级");
        MOCK_WEATHER.put("广州", "阵雨，31℃，南风2级");
        MOCK_WEATHER.put("深圳", "雷阵雨，30℃，南风3级");
        MOCK_WEATHER.put("杭州", "阴，27℃，无持续风向");
    }

    /** 工具名：模型调用时通过它定位本工具 */
    public static final String TOOL_NAME = "get_weather";

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public ToolDefinition definition() {
        // 入参：city（字符串，必填）
        Map<String, Object> cityProp = Map.of(
                "type", "string",
                "description", "要查询天气的城市名，如：北京、上海"
        );
        Map<String, Object> properties = Map.of("city", cityProp);
        return ToolDefinition.of(
                TOOL_NAME,
                "查询指定城市的实时天气。当用户询问某地天气、气温、是否下雨时使用。",
                properties,
                new String[]{"city"}
        );
    }

    @Override
    public CallToolResult execute(Map<String, Object> arguments) {
        // 1) 参数校验：把「业务失败」以 isError=true 返回，而非抛异常
        if (arguments == null || arguments.get("city") == null) {
            return CallToolResult.fail("缺少必填参数 city");
        }
        String city = String.valueOf(arguments.get("city")).trim();
        if (city.isEmpty()) {
            return CallToolResult.fail("参数 city 不能为空");
        }

        // 2) 查询数据
        String weather = MOCK_WEATHER.get(city);
        if (weather == null) {
            return CallToolResult.fail("暂无【" + city + "】的天气数据，请换一个城市试试");
        }

        // 3) 返回成功结果
        return CallToolResult.ok(city + "今天天气：" + weather);
    }
}