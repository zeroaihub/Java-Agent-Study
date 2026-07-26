package com.zero.ai.agentstudy.day09browseragent.service;

import com.example.agentstudy.day09browseragent.tool.BrowserTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * BrowserAgentService —— Day09 的「浏览器智能体」，把 LLM 与 Browser Tool 组合起来。
 *
 * <p><b>Agent = LLM(大脑) + Tools(手) + 循环</b>。用户用自然语言下达任务（如
 * “帮我打开 example.com 并总结内容”），LLM 自主决定调用哪个 {@code @Tool}、按什么
 * 顺序调用，框架执行浏览器动作后把结果回填，LLM 继续推理直到任务完成。</p>
 *
 * <p><b>隔离约束</b>：注入的 ChatClient 使用本模块独立 Bean 名 {@code day09ChatClient}
 * （通过 {@link Qualifier} 精确指定），与 Day01~Day08 的 ChatClient 互不干扰。</p>
 *
 * @author AI架构师
 */
@Slf4j
@Service
public class BrowserAgentService {

    private final ChatClient chatClient;
    private final BrowserTools browserTools;

    public BrowserAgentService(@Qualifier("day09ChatClient") ChatClient chatClient,
                               BrowserTools browserTools) {
        this.chatClient = chatClient;
        this.browserTools = browserTools;
    }

    /**
     * 以自然语言驱动浏览器完成任务。
     *
     * @param instruction 用户任务指令，如「打开 https://example.com 并总结页面内容」
     * @return Agent 的最终答复
     */
    public String run(String instruction) {
        log.info("[Day09][Agent] 收到任务: {}", instruction);
        String answer = chatClient.prompt()
                .system("""
                        你是一个浏览器自动化助手，可以通过工具真实地操作浏览器。
                        请根据用户意图，选择合适的浏览器工具完成任务；
                        如果需要页面内容再回答，请先调用读取工具获取内容再总结。
                        回答使用简洁中文。
                        """)
                .user(instruction)
                // 把浏览器工具挂到本次对话，LLM 可按需自主调用
                .tools(browserTools)
                .call()
                .content();
        log.info("[Day09][Agent] 任务完成");
        return answer;
    }
}