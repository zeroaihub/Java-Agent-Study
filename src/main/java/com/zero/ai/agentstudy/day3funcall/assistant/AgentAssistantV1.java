package com.zero.ai.agentstudy.day3funcall.assistant;

import com.zero.ai.agentstudy.day3funcall.tool.CalculatorTool03;
import com.zero.ai.agentstudy.day3funcall.tool.TimeTool03;
import com.zero.ai.agentstudy.day3funcall.tool.WeatherTool03;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

/**
 * Agent Assistant V1（第八章收官作品）
 *
 * 一个能"自动选择工具"的完整 Agent，支持：
 *   - 查天气（WeatherTool）
 *   - 查时间（TimeTool）
 *   - 计算器（CalculatorTool）
 *
 * 设计要点（企业级）：
 *   1. 用 system prompt 设定 Agent 人设与行为边界。
 *   2. 一次挂载三个工具，由 LLM 根据用户意图自动选择/编排。
 *   3. 统一入口 ask()，异常兜底，保证对外始终有回复。
 *
 * @author ZeroAi
 */
@Slf4j
@Service
public class AgentAssistantV1 {

    private final ChatClient chatClient;
    private final WeatherTool03 weatherTool03;
    private final TimeTool03 timeTool03;
    private final CalculatorTool03 calculatorTool03;

    /** Agent 的人设与行为边界 */
    private static final String SYSTEM_PROMPT = """
            你是一个专业、友好的生活助理 Agent，名叫"小智"。
            你可以帮用户查询天气、查询当前时间、进行数学计算。
            规则：
            1. 当需要实时数据或精确计算时，必须调用相应工具，不要自己臆测。
            2. 若用户问题超出你的工具能力范围（如订机票），礼貌说明你暂时做不到。
            3. 回答简洁、口语化，用中文。
            """;

    public AgentAssistantV1(ChatModel chatModel,
                            WeatherTool03 weatherTool03,
                            TimeTool03 timeTool03,
                            CalculatorTool03 calculatorTool03) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.weatherTool03 = weatherTool03;
        this.timeTool03 = timeTool03;
        this.calculatorTool03 = calculatorTool03;
    }

    /**
     * 统一入口：用户提问 → Agent 自动选工具 → 返回自然语言回答。
     *
     * @param userMessage 用户输入
     * @return Agent 回答
     */
    public String ask(String userMessage) {
        log.info("[AgentAssistantV1] 用户: {}", userMessage);
        try {
            String answer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userMessage)
                    .tools(weatherTool03, timeTool03, calculatorTool03)  // 三工具，LLM 自动选择
                    .call()
                    .content();
            log.info("[AgentAssistantV1] 小智: {}", answer);
            return answer;
        } catch (Exception e) {
            // 异常兜底：对外始终有回复，不把异常抛给用户
            log.error("[AgentAssistantV1] 处理失败", e);
            return "抱歉，我暂时遇到点问题，请稍后再试～";
        }
    }
}