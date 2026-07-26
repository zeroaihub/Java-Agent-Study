package com.zero.ai.agentstudy.day3funcall.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具（第四/五章）
 *
 * 教学要点：
 * 1. @Tool 注解把一个普通方法暴露给大模型，description 是 LLM 判断"何时调我"的唯一依据。
 * 2. @ToolParam 描述每个参数，帮助 LLM 正确传参。
 * 3. 方法返回结构化 JSON 字符串，而不是自然语言（第三章 Tool 设计原则）。
 * 4. LLM 不执行本方法，只输出"要调 getWeather(city=北京)"的意图，
 *    真正执行的是 Spring AI 框架 + 这段 Java 代码。
 *
 * @author ZeroAi
 */
@Slf4j
@Component
public class WeatherTool03 {

    /**
     * 查询指定城市的实时天气（模拟数据）。
     *
     * @param city 城市名
     * @return 结构化 JSON 字符串
     */
    @Tool(description = "查询指定城市的实时天气。当用户询问天气、气温、是否下雨、要不要带伞时使用")
    public String getWeather(
            @ToolParam(description = "城市名称") String city) {

        log.info("[WeatherTool] 被调用, city={}", city);

        // 1. 参数校验前置
        if (city == null || city.isBlank()) {
            return "{\"code\":\"INVALID_PARAM\",\"message\":\"城市名不能为空\"}";
        }

        // 2. 真实项目里这里会调天气 API（如和风天气）。这里用模拟数据演示
        String weather = mockWeather(city);

        // 3. 返回结构化 JSON（字段固定：city / temp / desc / humidity）
        return weather;
    }

    /**
     * 模拟天气数据：根据城市名简单编造，仅用于教学演示。
     */
    private String mockWeather(String city) {
        // 简单散列出不同结果，模拟"不同城市天气不同"
        int hash = Math.abs(city.hashCode());
        int temp = 15 + hash % 20;                 // 15~34 ℃
        String[] descList = {"晴", "多云", "小雨", "阴"};
        String desc = descList[hash % descList.length];
        int humidity = 40 + hash % 50;             // 40~89 %

        return String.format(
                "{\"city\":\"%s\",\"temp\":%d,\"desc\":\"%s\",\"humidity\":%d}",
                city, temp, desc, humidity);
    }
}