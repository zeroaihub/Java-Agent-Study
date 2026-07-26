package com.zero.ai.agentstudy.back.demo;

import com.alibaba.fastjson2.JSON;
import com.zero.ai.agentstudy.back.model.ChatCompletionRequest;
import com.zero.ai.agentstudy.back.model.ChatCompletionResponse;
import com.zero.ai.agentstudy.back.model.ChatMessage;
import com.zero.ai.agentstudy.back.service.AiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Demo8: 结构化输出 —— Agent 的基础
 *
 * 学习目标:
 *   1. 体验"自然语言 → JSON"的转换
 *   2. 掌握 response_format 三种模式
 *   3. 理解"为什么结构化输出是 Agent 的基础"
 *
 * 测试:
 *   POST /demo8/weather?text=北京今天晴,25度,有点风
 *   返回: {"city":"北京","weather":"晴","temperature":25,"windy":true}
 *
 * 重点: 程序能直接解析这些字段, 这就是 Agent 能"执行指令"的前提!
 *
 * @author ZeroAi
 */
@Slf4j
@RestController
@RequestMapping("/demo8")
@RequiredArgsConstructor
public class Demo8StructuredOutput {

    private final AiService aiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 方式1: 仅用 System Prompt 约束(不够可靠, 但最通用)
     * 所有模型都支持这种方式
     */
    @PostMapping("/weather/prompt")
    public WeatherInfo byPrompt(@RequestBody Text req) throws Exception {
        String systemPrompt = """
                你是一个信息提取助手。从用户输入中提取天气信息。
                你必须且只能输出合法的JSON, 不要输出任何其他文字。不要输出md格式
                JSON格式: {"city":"城市名","weather":"天气描述","temperature":温度数字,"windy":是否刮风布尔值}
                示例输入: 上海小雨,18度
                示例输出: {"city":"上海","weather":"小雨","temperature":18,"windy":false}
                """;

        ChatMessage system = ChatMessage.builder().role("system").content(systemPrompt).build();
        ChatMessage user = ChatMessage.builder().role("user").content(req.getText()).build();

        String json = aiService.chat(List.of(system, user));

        // 解析 JSON → Java 对象 (这就是程序能"理解"AI输出的关键!)
        WeatherInfo info = objectMapper.readValue(json.trim(), WeatherInfo.class);
        log.info("提取结果: {}", info);
        return info;
    }

    /**
     * 方式2: response_format = json_schema (推荐!)
     * 强制模型按给定 schema 输出合法JSON, 不会夹带废话。
     * 注意: 本地 LM Studio 只支持 "json_schema" 或 "text", 不支持 "json_object";
     *       OpenAI/DeepSeek 等则同时支持 json_object 与 json_schema。
     */
    @PostMapping("/weather/json")
    public WeatherInfo byJsonFormat(@RequestBody Text req) throws Exception {
        String systemPrompt = """
                从用户输入中提取天气信息, 输出JSON:
                {"city":"string","weather":"string","temperature":"number","windy":"boolean"}
                """;

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(
                        ChatMessage.builder().role("system").content(systemPrompt).build(),
                        ChatMessage.builder().role("user").content(req.getText()).build()
                ))
                .temperature(0.0)   // 结构化输出务必用低温度!
                .responseFormat(ChatCompletionRequest.ResponseFormat.builder()
                        .type("json_schema")
                        .jsonSchema(ChatCompletionRequest.JsonSchemaSchema.builder()
                                .name("weather_info")
                                .schema(weatherSchema())
                                .build())
                        .build())
                .build();

        ChatCompletionResponse resp = aiService.chat(request);
        String json = extractContent(resp);

        log.info("原始JSON输出: {}", json);
        return objectMapper.readValue(json.trim(), WeatherInfo.class);
    }

    /**
     * 构造天气信息的 JSON Schema, 约束模型只输出这些字段
     */
    private java.util.Map<String, Object> weatherSchema() {
        return java.util.Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", java.util.Map.of(
                        "city", java.util.Map.of("type", "string"),
                        "weather", java.util.Map.of("type", "string"),
                        "temperature", java.util.Map.of("type", "number"),
                        "windy", java.util.Map.of("type", "boolean")
                ),
                "required", List.of("city", "weather", "temperature", "windy")
        );
    }

    /**
     * 方式3: 模拟 Agent 的决策输出(重点!)
     *
     * 这是 Agent 的核心: LLM 决定"下一步做什么", 程序执行
     * 结构化输出让"决策"可被程序解析
     */
    @PostMapping("/agent/decide")
    public String agentDecide(@RequestBody Text userInput) throws Exception {
        String systemPrompt = """
                你是一个服务于中国人的智能助手的决策大脑。根据用户输入, 决定下一步动作。
                只输出JSON, 格式:
                {"action":"动作类型","tool":"工具名","args":{"参数":"值"},"reason":"决策理由"}

                严格规则(必须遵守):
                - 当 action 为 "call_tool" 时: tool 必须是具体工具名(禁止为 null/空), args 必须包含该工具所需参数(禁止为 null/空对象)。
                - 当 action 为 "reply" 时: tool 填 "none", args 必须包含 text 字段(即回复内容)。
                - 四个字段 action、tool、args、reason 都必须有值, 任何字段都不允许为 null。

                可选动作:
                - "call_tool": 需要调用工具
                - "reply": 直接回复用户

                示例:
                输入: "北京天气怎么样"
                输出: {"action":"call_tool","tool":"search_weather","args":{"city":"北京"},"reason":"需要查询天气"}

                输入: "你好"
                输出: {"action":"reply","tool":"none","args":{"text":"你好!有什么可以帮你?"},"reason":"简单问候无需工具"}
                """;

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(
                        ChatMessage.builder().role("system").content(systemPrompt).build(),
                        ChatMessage.builder().role("user").content(userInput.getText()).build()
                ))
                .temperature(0.0)
                .responseFormat(ChatCompletionRequest.ResponseFormat.builder()
                        .type("json_schema")
                        .jsonSchema(ChatCompletionRequest.JsonSchemaSchema.builder()
                                .name("agent_decision")
                                .schema(decisionSchema())
                                .build())
                        .build())
                .build();

        String json = extractContent(aiService.chat(request));
        AgentDecision decision = objectMapper.readValue(json.trim(), AgentDecision.class);

        // 程序根据决策执行 —— 这就是 Agent 的"行动"环节!
        log.info("Agent决策: {}", decision);

        return handlerTools(decision);
    }

    public String handlerTools( AgentDecision decision) {
        if (decision == null || decision.getTool() == null) {
            log.warn("decision is null or tool is null");
            return "";
        }
        if (decision.getTool().equals("search_weather")) {
            return search_weather(decision.getArgs());
        }
        return "";
    }

    public String search_weather(Object param) {
        if (param == null) {
            log.error("param is null");
            return "";
        }
        SearchWeatherParam searchWeatherParam = JSON.parseObject(JSON.toJSONString(param), SearchWeatherParam.class);
        if (searchWeatherParam.getCity().equals("北京")) {
            return "北京天气晴朗，温度25度，有点风";
        } else if (searchWeatherParam.getCity().equals("天津")) {
            return "天津天气不好，温度22度，没风";
        }

        return "";
    }
    @Data
    class SearchWeatherParam{
        String city;
    }
    /**
     * 构造 Agent 决策的 JSON Schema
     */
    private java.util.Map<String, Object> decisionSchema() {
        return java.util.Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", java.util.Map.of(
                        "action", java.util.Map.of("type", "string"),
                        "tool", java.util.Map.of("type", "string"),
                        "args", java.util.Map.of("type", "object"),
                        "reason", java.util.Map.of("type", "string")
                ),
                "required", List.of("action", "tool", "args", "reason")
        );
    }

    // ========== 结构化数据模型 ==========
    @Data
    @AllArgsConstructor
    @lombok.NoArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class WeatherInfo {
        private String city;
        private String weather;
        private Integer temperature;
        private Boolean windy;
    }

    @Data
    @AllArgsConstructor
    @lombok.NoArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentDecision {
        private String action;      // call_tool / reply
        private String tool;        // 工具名
        private Object args;        // 参数(任意结构)
        private String reason;      // 决策理由
    }

    /**
     * 提取模型输出的 JSON 文本。
     * 推理型模型(如 qwen3.5/DeepSeek-R1)会把正文放在 reasoning_content,
     * 而 content 为空; 这里做兼容: content 为空时回退到 reasoning_content。
     */
    private String extractContent(ChatCompletionResponse resp) {
        ChatMessage msg = resp.getChoices().get(0).getMessage();
        String content = msg.getContent();
        if (content == null || content.isBlank()) {
            content = msg.getReasoningContent();
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("模型未返回任何内容(content 与 reasoning_content 均为空)");
        }
        return content.trim();
    }

    @lombok.Data
    public static class Text {
        private String text;
    }
}
