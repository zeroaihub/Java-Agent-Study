package com.zero.ai.agentstudy.day3funcall.service;

import com.zero.ai.agentstudy.day3funcall.tool.WeatherTool03;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

/**
 * Day3 Agent 服务（第四章核心）
 *
 * 演示 Spring AI Tool Calling 的完整流程：
 *   用户提问 → ChatClient 带上工具 → LLM 决策是否调工具
 *            → Spring AI 自动执行工具 → 结果回传 LLM → LLM 组织自然语言回答
 *
 * 关键：你只需 chatClient.prompt().tools(...)，
 * 三阶段协议（发 tools / 解析 tool_calls / 回传结果 / 再请求）全部由 Spring AI 自动完成。
 *
 * @author ZeroAi
 */
@Slf4j
@Service
public class Day3AgentService {

    private final ChatClient chatClient;
    private final WeatherTool03 weatherTool03;

    /**
     * 构造注入：Spring AI 自动装配 ChatModel（基于 application.yml 的 spring.ai.openai 配置）。
     * 我们用它构建一个 ChatClient。
     */
    public Day3AgentService(ChatModel chatModel, WeatherTool03 weatherTool03) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.weatherTool03 = weatherTool03;
    }

    /**
     * 单工具对话：只挂载 WeatherTool。
     *
     * @param userMessage 用户输入
     * @return LLM 用工具结果组织好的自然语言回答
     */
    public String chatWithWeather(String userMessage) {
        log.info("[Day3Agent] 收到用户输入: {}", userMessage);

        String answer = chatClient.prompt()
                .user(userMessage)
                .tools(weatherTool03)   // ← 注册工具，Spring AI 自动处理后续 Tool Calling
                .call()
                .content();

        log.info("[Day3Agent] 最终回答: {}", answer);
        return answer;
    }

    /**
     * 多工具对话：传入任意多个工具对象，LLM 自动选择该调哪个。
     * 第五、六、八章会用到。
     *
     * @param userMessage 用户输入
     * @param tools       任意多个工具对象
     * @return 最终自然语言回答
     */
    public String chat(String userMessage, Object... tools) {
        log.info("[Day3Agent] 收到用户输入: {}, 挂载工具数={}", userMessage, tools.length);
        return chatClient.prompt()
                .user(userMessage)
                .tools(tools)
                .call()
                .content();
    }

    /**
     * 带系统提示词的对话（第七/八章）。
     * 用 system prompt 给 Agent 设定人设与行为边界，配合工具使用更可控。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户输入
     * @param tools        挂载的工具
     * @return 最终回答
     */
    public String chatWithSystem(String systemPrompt, String userMessage, Object... tools) {
        log.info("[Day3Agent] 带系统提示对话, 挂载工具数={}", tools.length);
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .tools(tools)
                .call()
                .content();
    }
}